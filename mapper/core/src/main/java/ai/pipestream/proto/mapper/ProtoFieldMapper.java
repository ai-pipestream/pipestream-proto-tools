package ai.pipestream.proto.mapper;

import ai.pipestream.proto.descriptors.DescriptorRegistry;
import ai.pipestream.proto.helpers.AnyHandler;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;

import java.util.List;

/**
 * Maps fields between protobuf messages using text rules or path accessors.
 */
public interface ProtoFieldMapper {

    DescriptorRegistry getDescriptorRegistry();

    AnyHandler getAnyHandler();

    Object getValue(MessageOrBuilder source, String path) throws MappingException;

    void setValue(Message.Builder targetBuilder, String path, Object value) throws MappingException;

    void appendValue(Message.Builder targetBuilder, String path, Object value) throws MappingException;

    void clearField(Message.Builder targetBuilder, String path) throws MappingException;

    void mapInPlace(Message.Builder builder, List<String> rules) throws MappingException;

    void map(Message source, Message.Builder targetBuilder, List<String> rules) throws MappingException;
}
