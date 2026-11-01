import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Counts assumption skips separately from passing tests in the standalone runner. */
public final class NativeJUnitReport {
    private NativeJUnitReport() {}

    public static void main(String[] args) throws ClassNotFoundException, IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected report path followed by test classes");
        }
        Class<?>[] classes = new Class<?>[args.length - 1];
        for (int index = 1; index < args.length; index++) {
            classes[index - 1] = Class.forName(args[index]);
        }
        final int[] assumptions = {0};
        JUnitCore junit = new JUnitCore();
        junit.addListener(new TextListener(System.out));
        junit.addListener(new RunListener() {
            @Override public void testAssumptionFailure(Failure failure) {
                assumptions[0]++;
            }
        });
        Result result = junit.run(classes);
        int passed = result.getRunCount() - result.getFailureCount() - assumptions[0];
        String json = "{\n"
                + "  \"executed\": " + result.getRunCount() + ",\n"
                + "  \"passed\": " + passed + ",\n"
                + "  \"failed\": " + result.getFailureCount() + ",\n"
                + "  \"assumptionSkipped\": " + assumptions[0] + ",\n"
                + "  \"ignored\": " + result.getIgnoreCount() + ",\n"
                + "  \"milliseconds\": " + result.getRunTime() + "\n}\n";
        Files.writeString(Path.of(args[0]), json, StandardCharsets.UTF_8);
        System.out.print(json);
        if (!result.wasSuccessful()) {
            System.exit(1);
        }
    }
}
