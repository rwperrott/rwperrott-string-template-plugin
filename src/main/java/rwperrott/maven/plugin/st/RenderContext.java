/*
 * See RenderMojo.java for license details/
 */
package rwperrott.maven.plugin.st;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import rwperrott.stringtemplate.v4.STContext;
import rwperrott.stringtemplate.v4.STUtils;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Paths.get;

final class RenderContext extends STContext implements Closeable {

    private final RenderMojo mojo;
    private final Log log;
    private final Path baseDir;
    private final Path stSrcDir;
    private final Path generatedSourcesJavaDir;
    //
    // Private stuff
    private final AtomicBoolean hasJavaFiles = new AtomicBoolean();
    private RenderContext(
            final RenderMojo mojo,
            final Path baseDir,
            final Log log) throws MojoFailureException {
        super(RenderContext.class.getClassLoader(),
              log::error);
        this.mojo = mojo;
        final Path templateSrcDir = get(mojo.templateSrcDir);
        // Resolve stSrcDir if relative
        if (!templateSrcDir.isAbsolute()) {
            stSrcDir = baseDir
                    .resolve("src")
                    .resolve("main")
                    .resolve(templateSrcDir);
        } else {
            stSrcDir = templateSrcDir;
        }

        // Ensure that stSrcDir exists and is a directory
        try {
            if (!exists(stSrcDir)) {
                throw new FileNotFoundException(stSrcDir.toString());
            }
            if (!isDirectory(stSrcDir)) {
                throw new IOException(format("'%s' not a directory", stSrcDir));
            }
        } catch (IOException e) {
            throw new MojoFailureException(
                    format("Invalid templateSrcDir '%s' (%s)",
                           templateSrcDir, e.getMessage()), e);
        }

        // Made use of optional plugin Dependencies feature to remove the need to explicitly build a ClassLoader,
        // instead get the classLoader of a plugin class.
        //
        this.log = log;
        this.baseDir = baseDir;
        this.generatedSourcesJavaDir = baseDir.resolve(GENERATED_SOURCES_JAVA);
    }

    Log log() {
        return log;
    }

    Path resolveTargetPath(String target) {
        Path targetPath = get(target);
        if (targetPath.isAbsolute())
            return targetPath;

        if (isJavaFile(targetPath))
            switch (targetPath.getName(0).toString()) {
                case ".":
                case "src":
                case "target":
                    break;
                default:
                    if (!targetPath.startsWith(RenderContext.GENERATED_SOURCES_JAVA)) {
                        return generatedSourcesJavaDir.resolve(targetPath);
                    }
                    break;
            }

        return baseDir.resolve(targetPath);
    }

    private static boolean isJavaFile(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    boolean isGeneratedSourcesJavaFile(Path path) {
        return path.startsWith(generatedSourcesJavaDir) && isJavaFile(path);
    }

    boolean failFast() {
        return mojo.failFast;
    }

    String resolveEncoding(String encoding) {
        return null == encoding ? mojo.sourceEncoding : encoding.toUpperCase(Locale.ROOT);
    }

    STUtils.TypeAndURL resolveTypeAndURL(final String source) throws IOException {
        return STUtils.resolveTypeAndURL(source, stSrcDir);
    }

    // Use by Template to register generated .java files.
    void onGeneratedSourcesJavaFile() {
        // Only set once to avoid redundant costs
        if (hasJavaFiles.compareAndSet(false, true)) {
            final MavenProject project = mojo.project;
            synchronized (project) { // Just-in-case method not Thread-safe.
                project.addCompileSourceRoot(generatedSourcesJavaDir.toString());
            }
        }
    }
    static final Path GENERATED_SOURCES_JAVA
            = get("target", "generated-sources", "java");

    static RenderContext of(final RenderMojo mojo) throws MojoFailureException {
        final Path baseDir = mojo.project.getBasedir().toPath().toAbsolutePath();
        return new RenderContext(mojo, baseDir, mojo.getLog());
    }
}
