package ai.pipestream.proto.chain;

import ai.pipestream.proto.cel.CelMappingRule;
import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.grpc.invoke.DynamicGrpcCalls;
import ai.pipestream.proto.grpc.invoke.DynamicGrpcStream;
import ai.pipestream.proto.mapper.MappingException;
import ai.pipestream.proto.mapper.ProtoFieldMapperImpl;
import ai.pipestream.proto.shapes.MessageJoiner;
import ai.pipestream.proto.shapes.MessageScope;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Metadata;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Joins two live gRPC server streams — the streaming execution story that is ours where
 * topic-to-topic joins are Kafka Streams'. Both sides are flow-controlled
 * {@link DynamicGrpcStream}s, so a fast stream cannot flood a slow one; unmatched entries
 * wait in bounded per-side buffers whose <em>oldest</em> entries are dropped on overflow —
 * memory is explicit, never unbounded.
 *
 * <p>{@code ZIP} pairs messages by arrival order; {@code KEYED} matches on a key read from
 * each side (a field path). Every match is joined into the target type through the standard
 * scoped rules — the same mapping surface as everywhere else. {@link #take} is poll-shaped,
 * like the stream it wraps: block up to the timeout, return up to {@code max} joined
 * messages.</p>
 */
public final class StreamJoiner implements AutoCloseable {

    public enum Mode { ZIP, KEYED }

    /**
     * One side of the join. {@code keyPath} is the field path whose value matches the other
     * side (KEYED mode); {@code name} is the scope name the join rules read it as.
     */
    public record Side(String name, Channel channel, MethodDescriptor method,
                       DynamicMessage request, String keyPath) {

        public Side {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(request, "request");
        }
    }

    private static final Duration PULL_SLICE = Duration.ofMillis(25);

    private final Mode mode;
    private final Side left;
    private final Side right;
    private final DynamicGrpcStream leftStream;
    private final DynamicGrpcStream rightStream;
    private final int bufferLimit;
    private final Descriptor target;
    private final List<String> rules;
    private final List<CelMappingRule> celRules;
    private final MessageJoiner joiner = new MessageJoiner();
    private final ProtoFieldMapperImpl keys;

    /** Unmatched messages: FIFO in ZIP mode, key-indexed FIFO in KEYED mode. */
    private final Deque<DynamicMessage> leftPending = new ArrayDeque<>();
    private final Deque<DynamicMessage> rightPending = new ArrayDeque<>();
    private final Map<String, Deque<DynamicMessage>> leftByKey = new LinkedHashMap<>();
    private final Map<String, Deque<DynamicMessage>> rightByKey = new LinkedHashMap<>();
    private int leftBuffered;
    private int rightBuffered;

    public StreamJoiner(Mode mode, Side left, Side right, int bufferLimit,
                        Descriptor target, List<String> rules, List<CelMappingRule> celRules) {
        if (mode == Mode.KEYED && (left.keyPath() == null || right.keyPath() == null)) {
            throw new IllegalArgumentException("KEYED joins need a keyPath on both sides");
        }
        if (bufferLimit <= 0) {
            throw new IllegalArgumentException("bufferLimit must be positive");
        }
        this.mode = mode;
        this.left = left;
        this.right = right;
        this.bufferLimit = bufferLimit;
        this.target = Objects.requireNonNull(target, "target");
        this.rules = List.copyOf(rules);
        this.celRules = List.copyOf(celRules);
        this.keys = new ProtoFieldMapperImpl(DescriptorRegistry.create());
        this.leftStream = DynamicGrpcCalls.openServerStream(left.channel(), left.method(),
                left.request(), CallOptions.DEFAULT, new Metadata());
        this.rightStream = DynamicGrpcCalls.openServerStream(right.channel(), right.method(),
                right.request(), CallOptions.DEFAULT, new Metadata());
    }

    /**
     * Takes up to {@code max} joined messages, waiting at most {@code timeout}. Returns
     * fewer (possibly none) on a quiet interval or when both streams have ended.
     *
     * @throws io.grpc.StatusRuntimeException once either stream has failed
     * @throws MappingException when a matched pair does not map into the target
     */
    public List<DynamicMessage> take(int max, Duration timeout) throws MappingException {
        List<DynamicMessage> joined = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (joined.size() < max) {
            // Pull whatever both sides have; the per-stream take is the flow-control valve.
            // Each message matches IMMEDIATELY against the other side's buffer (a symmetric
            // hash join) - buffering a whole batch first would let eviction discard
            // partners before they ever met.
            for (DynamicMessage message : leftStream.take(max, PULL_SLICE)) {
                offer(message, true, joined, max);
            }
            for (DynamicMessage message : rightStream.take(max, PULL_SLICE)) {
                offer(message, false, joined, max);
            }
            if (mode == Mode.ZIP) {
                zipMatches(joined, max);
            }
            if (isClosed() || System.nanoTime() >= deadline) {
                break;
            }
        }
        return joined;
    }

    /** Both streams ended and nothing more can match. */
    public boolean isClosed() {
        return leftStream.isClosed() && rightStream.isClosed();
    }

    private void offer(DynamicMessage message, boolean isLeft, List<DynamicMessage> out,
                       int max) throws MappingException {
        if (mode == Mode.ZIP) {
            Deque<DynamicMessage> pending = isLeft ? leftPending : rightPending;
            pending.addLast(message);
            if (pending.size() > bufferLimit) {
                pending.removeFirst(); // drop-oldest: memory stays bounded, by policy
            }
            return;
        }
        String key = keyOf(message, isLeft ? left : right);
        Map<String, Deque<DynamicMessage>> other = isLeft ? rightByKey : leftByKey;
        Deque<DynamicMessage> partners = other.get(key);
        if (partners != null && !partners.isEmpty() && out.size() < max) {
            DynamicMessage partner = partners.removeFirst();
            if (partners.isEmpty()) {
                other.remove(key);
            }
            if (isLeft) {
                rightBuffered--;
            } else {
                leftBuffered--;
            }
            out.add(isLeft ? join(message, partner) : join(partner, message));
            return;
        }
        Map<String, Deque<DynamicMessage>> index = isLeft ? leftByKey : rightByKey;
        index.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(message);
        if (isLeft) {
            leftBuffered++;
        } else {
            rightBuffered++;
        }
        evictOldest(index, isLeft);
    }

    private void evictOldest(Map<String, Deque<DynamicMessage>> index, boolean isLeft) {
        int buffered = isLeft ? leftBuffered : rightBuffered;
        while (buffered > bufferLimit && !index.isEmpty()) {
            var eldest = index.entrySet().iterator().next();
            eldest.getValue().removeFirst();
            if (eldest.getValue().isEmpty()) {
                index.remove(eldest.getKey());
            }
            buffered--;
        }
        if (isLeft) {
            leftBuffered = buffered;
        } else {
            rightBuffered = buffered;
        }
    }

    private void zipMatches(List<DynamicMessage> out, int max) throws MappingException {
        while (out.size() < max && !leftPending.isEmpty() && !rightPending.isEmpty()) {
            out.add(join(leftPending.removeFirst(), rightPending.removeFirst()));
        }
    }

    private DynamicMessage join(DynamicMessage leftMessage, DynamicMessage rightMessage)
            throws MappingException {
        MessageScope scope = MessageScope.builder()
                .add(left.name(), leftMessage)
                .add(right.name(), rightMessage)
                .build();
        return joiner.join(target, scope, rules, celRules);
    }

    private String keyOf(DynamicMessage message, Side side) throws MappingException {
        Object value = keys.getValue(message, side.keyPath(), true);
        return String.valueOf(value);
    }

    @Override
    public void close() {
        leftStream.close();
        rightStream.close();
    }
}
