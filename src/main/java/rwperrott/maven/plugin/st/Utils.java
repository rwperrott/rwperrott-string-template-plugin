/*
 * See RenderMojo.java for license details/
 */
package rwperrott.maven.plugin.st;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static java.lang.ThreadLocal.withInitial;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class Utils {
    private Utils() {
    }

    // Used by Template for reading JSON
    static final ThreadLocal<ObjectMapper> jsonMappers = withInitial(ObjectMapper::new);

    static boolean isJavaFile(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    // Support following links just-in-case
    static BasicFileAttributes existsAttributes(Path path, LinkOption... options) {
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

    @SuppressWarnings("UseSpecificCatch")
    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (Exception e) {
            // Just-in-case ATOMIC_MOVE not supported
            Files.move(from, to, REPLACE_EXISTING);
        }
    }

    static Throwable selectThrow(ExecutionException ex) {
        final Throwable cause = ex.getCause();
        if (null == cause ||
            null == ex.getMessage() ||
            ex.getMessage().equals(cause.getMessage()))
            return ex;
        return cause;
    }

}
