/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.data;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.html.types.SafeHtmlProto;
import com.google.common.html.types.SafeScriptProto;
import com.google.common.html.types.SafeStyleProto;
import com.google.common.html.types.SafeStyleSheetProto;
import com.google.common.html.types.SafeUrlProto;
import com.google.common.html.types.TrustedResourceUrlProto;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedInts;
import com.google.common.primitives.UnsignedLongs;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor.Syntax;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.template.soy.data.internal.DictImpl;
import com.google.template.soy.data.internal.DictImpl.RuntimeType;
import com.google.template.soy.data.internal.ListImpl;
import com.google.template.soy.data.internal.SoyMapImpl;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.FloatData;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.types.proto.FieldVisitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A collaborator for {@link SoyProtoValue} that handles the interpretation of proto fields. */
abstract class FieldInterpreter {
  private static final FieldVisitor<FieldInterpreter> VISITOR =
      new FieldVisitor<FieldInterpreter>() {
        @Override
        protected FieldInterpreter visitLongAsInt() {
          return LONG_AS_INT;
        }

        @Override
        protected FieldInterpreter visitUnsignedInt() {
          return UNSIGNED_INT;
        }

        @Override
        protected FieldInterpreter visitUnsignedLongAsString() {
          return UNSIGNEDLONG_AS_STRING;
        }

        @Override
        protected FieldInterpreter visitLongAsString() {
          return LONG_AS_STRING;
        }

        @Override
        protected FieldInterpreter visitBool() {
          return BOOL;
        }

        @Override
        protected FieldInterpreter visitBytes() {
          return BYTES;
        }

        @Override
        protected FieldInterpreter visitString() {
          return STRING;
        }

        @Override
        protected FieldInterpreter visitDoubleAsFloat() {
          return DOUBLE_AS_FLOAT;
        }

        @Override
        protected FieldInterpreter visitFloat() {
          return FLOAT;
        }

        @Override
        protected FieldInterpreter visitInt() {
          return INT;
        }

        @Override
        protected FieldInterpreter visitSafeHtml() {
          return SAFE_HTML_PROTO;
        }

        @Override
        protected FieldInterpreter visitSafeScript() {
          return SAFE_SCRIPT_PROTO;
        }

        @Override
        protected FieldInterpreter visitSafeStyle() {
          return SAFE_STYLE_PROTO;
        }

        @Override
        protected FieldInterpreter visitSafeStyleSheet() {
          return SAFE_STYLE_SHEET_PROTO;
        }

        @Override
        protected FieldInterpreter visitSafeUrl() {
          return SAFE_URL_PROTO;
        }

        @Override
        protected FieldInterpreter visitTrustedResourceUrl() {
          return TRUSTED_RESOURCE_URI_PROTO;
        }

        @Override
        protected FieldInterpreter visitMessage(Descriptor messageType) {
          return PROTO_MESSAGE;
        }

        @Override
        protected FieldInterpreter visitEnum(EnumDescriptor enumType) {
          return enumTypeField(enumType);
        }

        @Override
        protected FieldInterpreter visitMap(
            FieldDescriptor mapField, FieldInterpreter keyValue, FieldInterpreter valueValue) {
          return getMapType(mapField, keyValue, valueValue);
        }

        @Override
        protected FieldInterpreter visitJspbMap(
            FieldDescriptor keyField, FieldInterpreter scalarInterpreter) {
          return getJspbMapType(scalarInterpreter, keyField);
        }

        @Override
        protected FieldInterpreter visitRepeated(FieldInterpreter value) {
          return getListType(value);
        }
      };

  /** Creates a {@link FieldInterpreter} for the given field. */
  static FieldInterpreter create(FieldDescriptor fieldDescriptor) {
    return FieldVisitor.visitField(fieldDescriptor, VISITOR);
  }

  private static FieldInterpreter getListType(final FieldInterpreter local) {
    return new FieldInterpreter() {
      @Override
      public SoyValue soyFromProto(Object field) {
        @SuppressWarnings("unchecked")
        List<?> entries = (List<?>) field;
        ImmutableList.Builder<SoyValueProvider> builder = ImmutableList.builder();
        for (Object item : entries) {
          builder.add(local.soyFromProto(item));
        }
        return ListImpl.forProviderList(builder.build());
      }

      @Override
      Object protoFromSoy(SoyValue field) {
        SoyList list = (SoyList) field;
        List<Object> uninterpretedValues = new ArrayList<>();
        for (SoyValue item : list.asResolvedJavaList()) {
          uninterpretedValues.add(local.protoFromSoy(item));
        }
        return uninterpretedValues;
      }
    };
  }

  private static FieldInterpreter getMapType(
      final FieldDescriptor mapField,
      final FieldInterpreter keyField,
      final FieldInterpreter valueField) {
    final Descriptor messageDescriptor = mapField.getMessageType();
    final FieldDescriptor keyDescriptor = messageDescriptor.getFields().get(0);
    final FieldDescriptor valueDescriptor = messageDescriptor.getFields().get(1);
    return new FieldInterpreter() {

      @Override
      SoyValue soyFromProto(Object field) {
        @SuppressWarnings("unchecked")
        List<Message> entries = (List<Message>) field;
        ImmutableMap.Builder<SoyValue, SoyValueProvider> builder = ImmutableMap.builder();
        for (Message message : entries) {
          SoyValue key = keyField.soyFromProto(message.getField(keyDescriptor)).resolve();
          builder.put(key, valueField.soyFromProto(message.getField(valueDescriptor)));
        }
        return SoyMapImpl.forProviderMap(builder.build());
      }

      @Override
      Object protoFromSoy(SoyValue field) {
        SoyMap map = (SoyMap) field;
        // Proto map fields use a non-standard API. A protobuf map is actually a repeated list of
        // MapEntry quasi-messages, which one cannot mutate in-place inside a map.
        ImmutableList.Builder<Message> mapEntries = ImmutableList.builder();
        Message.Builder defaultInstance =
            DynamicMessage.newBuilder(messageDescriptor.getContainingType());
        for (Map.Entry<? extends SoyValue, ? extends SoyValueProvider> entry :
            map.asJavaMap().entrySet()) {
          Message.Builder entryBuilder = defaultInstance.newBuilderForField(mapField);
          entryBuilder.setField(keyDescriptor, keyField.protoFromSoy(entry.getKey()));
          entryBuilder.setField(
              valueDescriptor, valueField.protoFromSoy(entry.getValue().resolve()));
          mapEntries.add(entryBuilder.build());
        }
        return mapEntries.build();
      }
    };
  }

  /**
   * Proto {@code map} fields are handled by {@link #getMapType}. But before protos had a map type,
   * JSPB had a {@code map_key} field annotation that simulated map behavior at runtime. They're
   * still out there, somewhere, so we have to support them.
   *
   * <p>TODO(b/70671325): Investigate if we can drop support for this.
   */
  private static FieldInterpreter getJspbMapType(
      final FieldInterpreter scalarImpl, final FieldDescriptor keyFieldDescriptor) {
    return new FieldInterpreter() {
      @Override
      public SoyValue soyFromProto(Object field) {
        @SuppressWarnings("unchecked")
        List<Message> entries = (List<Message>) field;
        ImmutableMap.Builder<String, SoyValueProvider> builder = ImmutableMap.builder();
        for (Message message : entries) {
          String key = (String) message.getField(keyFieldDescriptor);
          if (key.isEmpty()) {
            // Ignore empty keys.
            continue;
          }
          builder.put(key, scalarImpl.soyFromProto(message));
        }
        return DictImpl.forProviderMap(builder.build(), RuntimeType.LEGACY_OBJECT_MAP_OR_RECORD);
      }

      @Override
      Object protoFromSoy(SoyValue field) {
        // TODO(lukes): this is supportable, but mapkey fields are deprecated...  add support
        // when/if someone starts asking for it.
        throw new UnsupportedOperationException(
            "assigning to mapkey fields is not currently supported");
      }
    };
  }

  /** A {@link FieldInterpreter} for bytes typed fields. */
  private static final FieldInterpreter BYTES =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return StringData.forValue(
              BaseEncoding.base64().encode(((ByteString) field).toByteArray()));
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ByteString.copyFrom(BaseEncoding.base64().decode(field.stringValue()));
        }
      };

  /** A {@link FieldInterpreter} for bool typed fields. */
  private static final FieldInterpreter BOOL =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return BooleanData.forValue((Boolean) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return field.booleanValue();
        }
      };

  /** A {@link FieldInterpreter} for int typed fields. */
  private static final FieldInterpreter INT =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return IntegerData.forValue(((Number) field).longValue());
        }


        @Override
        Object protoFromSoy(SoyValue field) {
          return Ints.saturatedCast(field.longValue());
        }
      };

  /** A {@link FieldInterpreter} for int typed fields. */
  private static final FieldInterpreter UNSIGNED_INT =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return IntegerData.forValue(UnsignedInts.toLong(((Number) field).intValue()));
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return UnsignedInts.saturatedCast(field.longValue());
        }
      };

  /** A {@link FieldInterpreter} for int64 typed fields interpreted as soy ints. */
  private static final FieldInterpreter LONG_AS_INT =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return IntegerData.forValue(((Long) field).longValue());
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return field.longValue();
        }
      };

  /** A {@link FieldInterpreter} for int64 typed fields interpreted as soy strings. */
  private static final FieldInterpreter LONG_AS_STRING =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return StringData.forValue(field.toString());
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return Long.parseLong(field.stringValue());
        }
      };

  /**
   * A {@link FieldInterpreter} for uint64 typed fields interpreted as soy strings.
   *
   * <p>TODO(lukes): when soy fully switches to java8 use the methods on java.lang.Long
   */
  private static final FieldInterpreter UNSIGNEDLONG_AS_STRING =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return StringData.forValue(UnsignedLongs.toString((Long) field));
        }


        @Override
        Object protoFromSoy(SoyValue field) {
          return UnsignedLongs.parseUnsignedLong(field.stringValue());
        }
      };

  /** A {@link FieldInterpreter} for float typed fields. */
  private static final FieldInterpreter FLOAT =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return FloatData.forValue(((Float) field).floatValue());
        }


        @Override
        Object protoFromSoy(SoyValue field) {
          return (float) field.floatValue();
        }
      };

  /** A {@link FieldInterpreter} for double typed fields interpreted as soy floats. */
  private static final FieldInterpreter DOUBLE_AS_FLOAT =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return FloatData.forValue(((Double) field).doubleValue());
        }


        @Override
        Object protoFromSoy(SoyValue field) {
          return field.floatValue();
        }
      };

  /** A {@link FieldInterpreter} for string typed fields. */
  private static final FieldInterpreter STRING =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return StringData.forValue(field.toString());
        }


        @Override
        Object protoFromSoy(SoyValue field) {
          return field.stringValue();
        }
      };

  private static final FieldInterpreter SAFE_HTML_PROTO =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SanitizedContents.fromSafeHtmlProto((SafeHtmlProto) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SanitizedContent) field).toSafeHtmlProto();
        }
      };

  private static final FieldInterpreter SAFE_SCRIPT_PROTO =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SanitizedContents.fromSafeScriptProto((SafeScriptProto) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SanitizedContent) field).toSafeScriptProto();
        }
      };

  private static final FieldInterpreter SAFE_STYLE_PROTO =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SanitizedContents.fromSafeStyleProto((SafeStyleProto) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SanitizedContent) field).toSafeStyleProto();
        }
      };

  private static final FieldInterpreter SAFE_STYLE_SHEET_PROTO =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SanitizedContents.fromSafeStyleSheetProto((SafeStyleSheetProto) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SanitizedContent) field).toSafeStyleSheetProto();
        }
      };

  private static final FieldInterpreter SAFE_URL_PROTO =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SanitizedContents.fromSafeUrlProto((SafeUrlProto) field);
        }


        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SanitizedContent) field).toSafeUrlProto();
        }
      };
  private static final FieldInterpreter TRUSTED_RESOURCE_URI_PROTO =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SanitizedContents.fromTrustedResourceUrlProto((TrustedResourceUrlProto) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SanitizedContent) field).toTrustedResourceUrlProto();
        }
      };
  /**
   * Returns a {@link FieldInterpreter} that has the given type and delegates to the
   * SoyValueConverter for interpretation.
   */
  private static final FieldInterpreter enumTypeField(final EnumDescriptor enumDescriptor) {
    return new FieldInterpreter() {

      @Override
      public SoyValue soyFromProto(Object field) {
        int value;
        if (field instanceof ProtocolMessageEnum) {
          value = ((ProtocolMessageEnum) field).getNumber();
        } else {
          // The value will be an EnumValueDescriptor when fetched via reflection or a
          // ProtocolMessageEnum otherwise.  Who knows why.
          value = ((EnumValueDescriptor) field).getNumber();
        }
        return IntegerData.forValue(value);
      }

      @Override
      Object protoFromSoy(SoyValue field) {
        // The proto reflection api wants the EnumValueDescriptor, not the actual enum instance
        int value = field.integerValue();
        // in proto3 we preserve unknown enum values (for consistency with jbcsrc), but for proto2
        // we don't, and so if the field is unknown we will return null which will trigger an NPE
        // again, for consistency with jbcsrc.
        if (enumDescriptor.getFile().getSyntax() == Syntax.PROTO3) {
          return enumDescriptor.findValueByNumberCreatingIfUnknown(value);
        }
        return enumDescriptor.findValueByNumber(value);
      }
    };
  }

  private static final FieldInterpreter PROTO_MESSAGE =
      new FieldInterpreter() {
        @Override
        public SoyValue soyFromProto(Object field) {
          return SoyProtoValueImpl.create((Message) field);
        }

        @Override
        Object protoFromSoy(SoyValue field) {
          return ((SoyProtoValue) field).getProto();
        }
      };

  private FieldInterpreter() {}

  /** Returns the SoyValue for the Tofu representation of the given field. */
  abstract SoyValue soyFromProto(Object field);

  /**
   * Returns an object that can be assigned to a proto field via the proto reflection APIs.
   *
   * <p>Generally this is the inverse operation of {@link #soyFromProto}.
   */
  abstract Object protoFromSoy(SoyValue field);
}