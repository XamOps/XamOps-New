import java.lang.reflect.Field;

public class CheckTypeTag {
    public static void main(String[] args) {
        try {
            Class<?> typeTagClass = Class.forName("com.sun.tools.javac.code.TypeTag");
            System.out.println("Fields in TypeTag:");
            for (Field field : typeTagClass.getFields()) {
                System.out.println(field.getName());
            }
            for (Field field : typeTagClass.getDeclaredFields()) {
                System.out.println("Declared: " + field.getName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
