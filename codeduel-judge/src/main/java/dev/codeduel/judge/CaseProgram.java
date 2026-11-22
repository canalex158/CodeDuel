package dev.codeduel.judge;

import com.google.gson.JsonElement;
import dev.codeduel.shared.Protocol;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

final class CaseProgram {
    private CaseProgram() {}

    static String render(Suite suite, Suite.Case test) {
        String types = suite.parameterTypes().stream().map(type -> type + ".class").collect(Collectors.joining(", "));
        String arguments = IntStream.range(0, suite.parameterTypes().size())
                .mapToObj(i -> literal(suite.parameterTypes().get(i), test.arguments().get(i)))
                .collect(Collectors.joining(", "));
        return """
                import java.io.*;
                import java.lang.reflect.*;
                import java.nio.charset.StandardCharsets;
                import java.util.*;
                public final class CodeDuelCase {
                    public static void main(String[] ignored) throws Throwable {
                        PrintStream result = System.out;
                        PrintStream silent = new PrintStream(OutputStream.nullOutputStream());
                        System.setOut(silent);
                        System.setErr(silent);
                        System.setIn(InputStream.nullInputStream());

                        Method method;
                        try {
                            Class<?> solution = Class.forName("Solution", false, CodeDuelCase.class.getClassLoader());
                            method = solution.getDeclaredMethod("solve", new Class<?>[]{%s});
                        } catch (ClassNotFoundException | NoSuchMethodException e) {
                            System.exit(12);
                            return;
                        }
                        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())
                            || !Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                            || method.getReturnType() != %s.class) {
                            System.exit(12);
                            return;
                        }
                        Object value = method.invoke(null, new Object[]{%s});
                        String encoded;
                        if (value instanceof int[] ints) {
                            encoded = Arrays.toString(ints);
                        } else {
                            encoded = String.valueOf(value);
                        }
                        if (value == null && method.getReturnType() == String.class) {
                            System.exit(13);
                            return;
                        }
                        result.println(Base64.getEncoder().encodeToString(encoded.getBytes(StandardCharsets.UTF_8)));
                    }
                }
                """.formatted(types, suite.returnType(), arguments);
    }

    static String expected(Suite suite, Suite.Case test) {
        JsonElement value = test.expected();
        if ("int[]".equals(suite.returnType())) {
            return StreamSupport.stream(value.getAsJsonArray().spliterator(), false).map(JsonElement::getAsString)
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        return value.getAsString();
    }

    private static String literal(String type, JsonElement value) {
        return switch (type) {
            case "String" -> Protocol.JSON.toJson(value);
            case "int", "boolean" -> value.getAsString();
            case "int[]", "int[][]" -> {
                String itemType = type.substring(0, type.length() - 2);
                yield StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                        .map(item -> literal(itemType, item))
                        .collect(Collectors.joining(", ", "new " + type + "{", "}"));
            }
            default -> throw new IllegalArgumentException("Tipo de caso no admitido: " + type);
        };
    }
}
