package ai.pipestream.proto.helpers;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.google.protobuf.DescriptorProtos.DescriptorProto;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the TypeConverter class.
 */
public class TypeConverterTest {

    private static TypeConverter converter;
    private static Descriptor testMessageDescriptor;

    @BeforeAll
    static void setUp() throws DescriptorValidationException {
        converter = new TypeConverter();
        FileDescriptor fileDescriptor = createTestFileDescriptor();
        testMessageDescriptor = fileDescriptor.findMessageTypeByName("TestMessage");
        assertNotNull(testMessageDescriptor);
    }

    @Test
    void testToValueFromPrimitives() {
        // String
        Value stringValue = converter.toValue("hello");
        assertEquals("hello", stringValue.getStringValue());

        // Integer/Long
        Value intValue = converter.toValue(42);
        assertEquals(42.0, intValue.getNumberValue(), 0.001);

        Value longValue = converter.toValue(123456789L);
        assertEquals(123456789.0, longValue.getNumberValue(), 0.001);

        // Double/Float
        Value doubleValue = converter.toValue(3.14159);
        assertEquals(3.14159, doubleValue.getNumberValue(), 0.00001);

        Value floatValue = converter.toValue(2.5f);
        assertEquals(2.5, floatValue.getNumberValue(), 0.001);

        // Boolean
        Value boolValue = converter.toValue(true);
        assertTrue(boolValue.getBoolValue());

        // Null
        Value nullValue = converter.toValue(null);
        assertEquals(NullValue.NULL_VALUE, nullValue.getNullValue());
    }

    @Test
    void testToValueFromList() {
        List<Object> list = Arrays.asList("a", 1, true, null);
        Value listValue = converter.toValue(list);

        assertTrue(listValue.hasListValue());
        ListValue lv = listValue.getListValue();
        assertEquals(4, lv.getValuesCount());
        assertEquals("a", lv.getValues(0).getStringValue());
        assertEquals(1.0, lv.getValues(1).getNumberValue(), 0.001);
        assertTrue(lv.getValues(2).getBoolValue());
        assertEquals(NullValue.NULL_VALUE, lv.getValues(3).getNullValue());
    }

    @Test
    void testToValueFromStruct() {
        Struct struct = Struct.newBuilder()
                .putFields("name", Value.newBuilder().setStringValue("test").build())
                .putFields("count", Value.newBuilder().setNumberValue(42).build())
                .build();

        Value structValue = converter.toValue(struct);
        assertTrue(structValue.hasStructValue());
        assertEquals(struct, structValue.getStructValue());
    }

    @Test
    void testFromValue() {
        // String
        Value stringValue = Value.newBuilder().setStringValue("world").build();
        assertEquals("world", converter.fromValue(stringValue));

        // Number
        Value numberValue = Value.newBuilder().setNumberValue(99.5).build();
        assertEquals(99.5, converter.fromValue(numberValue));

        // Bool
        Value boolValue = Value.newBuilder().setBoolValue(false).build();
        assertEquals(false, converter.fromValue(boolValue));

        // Null
        Value nullValue = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        assertNull(converter.fromValue(nullValue));

        // List
        ListValue listValue = ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue("x"))
                .addValues(Value.newBuilder().setNumberValue(5))
                .build();
        Value listVal = Value.newBuilder().setListValue(listValue).build();
        List<?> result = (List<?>) converter.fromValue(listVal);
        assertEquals(2, result.size());
        assertEquals("x", result.get(0));
        assertEquals(5.0, result.get(1));
    }

    @Test
    void testMessageToStruct() {
        Message testMessage = DynamicMessage.newBuilder(testMessageDescriptor)
                .setField(testMessageDescriptor.findFieldByName("name"), "TestName")
                .setField(testMessageDescriptor.findFieldByName("age"), 25L)
                .setField(testMessageDescriptor.findFieldByName("active"), true)
                .build();

        Struct struct = converter.messageToStruct(testMessage);
        assertNotNull(struct);
        assertEquals("TestName", struct.getFieldsOrThrow("name").getStringValue());
        assertEquals(25.0, struct.getFieldsOrThrow("age").getNumberValue(), 0.001);
        assertTrue(struct.getFieldsOrThrow("active").getBoolValue());
    }

    @Test
    void testStructToMessage() {
        Struct struct = Struct.newBuilder()
                .putFields("name", Value.newBuilder().setStringValue("StructName").build())
                .putFields("age", Value.newBuilder().setNumberValue(30).build())
                .putFields("active", Value.newBuilder().setBoolValue(false).build())
                .build();

        DynamicMessage message = converter.structToMessage(struct, testMessageDescriptor);
        assertNotNull(message);
        assertEquals("StructName", message.getField(testMessageDescriptor.findFieldByName("name")));
        assertEquals(30L, message.getField(testMessageDescriptor.findFieldByName("age")));
        assertEquals(false, message.getField(testMessageDescriptor.findFieldByName("active")));
    }

    @Test
    void testConvertToFieldType() throws Exception {
        // String to int
        Object intResult = converter.convertToFieldType("123", testMessageDescriptor.findFieldByName("age"));
        assertEquals(123L, intResult);

        // Number to string
        Object stringResult = converter.convertToFieldType(456, testMessageDescriptor.findFieldByName("name"));
        assertEquals("456", stringResult);

        // String to boolean
        Object boolResult = converter.convertToFieldType("true", testMessageDescriptor.findFieldByName("active"));
        assertEquals(true, boolResult);

        // Direct compatible type
        Object directResult = converter.convertToFieldType("direct", testMessageDescriptor.findFieldByName("name"));
        assertEquals("direct", directResult);
    }

    @Test
    void testConvertToFieldTypeIncompatible() {
        // Try to convert incompatible types
        assertThrows(IllegalArgumentException.class, () -> {
            converter.convertToFieldType("not-a-number", testMessageDescriptor.findFieldByName("age"));
        });
    }

    @Test
    void testMessageToStructWithRepeatedField() throws DescriptorValidationException {
        FileDescriptor fd = createRepeatedFieldDescriptor();
        Descriptor repeatedDescriptor = fd.findMessageTypeByName("RepeatedMessage");

        Message message = DynamicMessage.newBuilder(repeatedDescriptor)
                .addRepeatedField(repeatedDescriptor.findFieldByName("tags"), "tag1")
                .addRepeatedField(repeatedDescriptor.findFieldByName("tags"), "tag2")
                .addRepeatedField(repeatedDescriptor.findFieldByName("tags"), "tag3")
                .build();

        Struct struct = converter.messageToStruct(message);
        assertTrue(struct.getFieldsOrThrow("tags").hasListValue());
        ListValue tags = struct.getFieldsOrThrow("tags").getListValue();
        assertEquals(3, tags.getValuesCount());
        assertEquals("tag1", tags.getValues(0).getStringValue());
        assertEquals("tag2", tags.getValues(1).getStringValue());
        assertEquals("tag3", tags.getValues(2).getStringValue());
    }

    private static FileDescriptor createTestFileDescriptor() throws DescriptorValidationException {
        DescriptorProto testMessageProto = DescriptorProto.newBuilder()
                .setName("TestMessage")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("age").setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("active").setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_BOOL))
                .build();

        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test_converter.proto")
                .setPackage("ai.pipestream.test")
                .addMessageType(testMessageProto)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[]{});
    }

    private static FileDescriptor createRepeatedFieldDescriptor() throws DescriptorValidationException {
        DescriptorProto repeatedMessageProto = DescriptorProto.newBuilder()
                .setName("RepeatedMessage")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("tags").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();

        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test_repeated.proto")
                .setPackage("ai.pipestream.test")
                .addMessageType(repeatedMessageProto)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[]{});
    }
}
