package io.papermc.paper.configuration.type;

import com.mojang.logging.LogUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.spongepowered.configurate.serialize.ScalarSerializer;
import org.spongepowered.configurate.serialize.SerializationException;

import java.lang.reflect.Type;
import java.util.OptionalInt;
import java.util.function.Predicate;

public record IntOrDefault(OptionalInt value) {
    private static final String DEFAULT_VALUE = "default";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final IntOrDefault USE_DEFAULT = new IntOrDefault(OptionalInt.empty());
    public static final ScalarSerializer<IntOrDefault> SERIALIZER = new Serializer();

    public int or(final int fallback) {
        return this.value.orElse(fallback);
    }

    private static final class Serializer extends ScalarSerializer<IntOrDefault> {
        Serializer() {
            super(IntOrDefault.class);
        }

        @Override
        public IntOrDefault deserialize(final Type type, final Object obj) throws SerializationException {
            if (obj instanceof String string) {
                if (DEFAULT_VALUE.equalsIgnoreCase(string)) {
                    return USE_DEFAULT;
                }
                if (NumberUtils.isParsable(string)) {
                    return new IntOrDefault(OptionalInt.of(Integer.parseInt(string)));
                }
            } else if (obj instanceof Number num) {
                if (num.intValue() != num.doubleValue() || num.intValue() != num.longValue()) {
                    LOGGER.error("{} cannot be converted to an integer without losing information", num);
                }
                return new IntOrDefault(OptionalInt.of(num.intValue()));
            }
            throw new SerializationException(obj + "(" + type + ") is not a integer or '" + DEFAULT_VALUE + "'");
        }

        @Override
        protected Object serialize(final IntOrDefault item, final Predicate<Class<?>> typeSupported) {
            final OptionalInt value = item.value();
            if (value.isPresent()) {
                return value.getAsInt();
            } else {
                return DEFAULT_VALUE;
            }
        }
    }
}
