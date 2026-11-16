package kh.cmt.shared.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.util.List;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.Sensitive;
import kh.cmt.shared.security.SensitiveField;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Masks {@link Sensitive} values in every JSON response: a value the caller's role may not see is written as
 * {@link FieldMask#MASK}. Registered with Spring's ObjectMapper automatically (it is a Jackson module bean). The
 * decision is made each time a response is written, so a change of the role's settings applies on the next request.
 * {@link FieldMask} is looked up only then: without it a sensitive value cannot be written at all (never unmasked).
 */
@Component
class SensitiveFieldsModule extends SimpleModule {

    SensitiveFieldsModule(ObjectProvider<FieldMask> mask) {
        super("cmt-sensitive-fields");
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription description,
                    List<BeanPropertyWriter> properties) {
                // changed in place: Jackson goes on editing this list after the modifiers have run
                properties.replaceAll(property -> {
                    Sensitive sensitive = property.getAnnotation(Sensitive.class);
                    return sensitive == null ? property : new MaskingWriter(property, sensitive.value(), mask);
                });
                return properties;
            }
        });
    }

    /** Writes the property as usual, or the mask when the caller may not see it. */
    static final class MaskingWriter extends BeanPropertyWriter {

        private final SensitiveField field;
        private final ObjectProvider<FieldMask> mask;

        MaskingWriter(BeanPropertyWriter base, SensitiveField field, ObjectProvider<FieldMask> mask) {
            super(base);
            this.field = field;
            this.mask = mask;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (get(bean) == null || mask.getObject().allows(field)) {
                super.serializeAsField(bean, gen, prov);
                return;
            }
            gen.writeFieldName(_name);
            gen.writeString(FieldMask.MASK);
        }

        @Override
        public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (get(bean) == null || mask.getObject().allows(field)) {
                super.serializeAsElement(bean, gen, prov);
                return;
            }
            gen.writeString(FieldMask.MASK);
        }
    }
}
