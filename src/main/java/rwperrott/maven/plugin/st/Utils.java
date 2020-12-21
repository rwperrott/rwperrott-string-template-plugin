/*
 * See RenderMojo.java for license details/
 */
package rwperrott.maven.plugin.st;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.Paths.get;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static rwperrott.stringtemplate.v4.STUtils.validateAttributes;

final class Utils {
    private Utils() {
    }

    static final Path GENERATED_SOURCES_JAVA
            = get("target", "generated-sources", "java");

    private static final ObjectMapper mapper = new ObjectMapper();
    public static final ObjectReader reader = mapper.reader();

    @SuppressWarnings("SameParameterValue")
    static <V> Map<String, V> readAndCheckJSONMap(final String json, final String name, final int checkDepth) throws IOException {
        return validateAttributes(reader.readValue(json, Map.class), name, checkDepth);
    }

    // Used by Template.init()

    /**
     * Supports following links, just-in-case need this later.
     */
    // Used by Template::init
    @SuppressWarnings("unused")
    static BasicFileAttributes existsAttributes(final Path path, final LinkOption... options) {
        try {
            // Merge in Files.exists-like code, to simplify use.
            boolean followLinks = true;
            if (options != null)
                for (LinkOption opt : options)
                    if (opt == NOFOLLOW_LINKS)
                        followLinks = false;
                    else if (null == opt)
                        throw new IllegalArgumentException("null in options: " + Arrays.toString(options));
            final FileSystemProvider provider = path.getFileSystem().provider();
            if (followLinks)
                provider.checkAccess(path);
            // Allowed access, so try and readAttributes.
            return provider.readAttributes(path, BasicFileAttributes.class, options);
        } catch (IOException e) {
            return null;
        }
    }

    // Used by Template::call
    @SuppressWarnings({"UseSpecificCatch", "unused"})
    static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (Exception e) {
            // Just-in-case ATOMIC_MOVE not supported
            Files.move(from, to, REPLACE_EXISTING);
        }
    }

    static Throwable selectThrow(final ExecutionException ex) {
        final Throwable cause = ex.getCause();
        if (null == cause ||
            null == ex.getMessage() ||
            ex.getMessage().equals(cause.getMessage()))
            return ex;
        return cause;
    }

    static boolean isJavaFile(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }
}
