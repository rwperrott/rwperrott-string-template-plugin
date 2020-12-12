/*
 * See RenderMojo.java for license details/
 */
package rwperrott.maven.plugin.st;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import rwperrott.stringtemplate.v4.STContext;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static java.nio.charset.Charset.defaultCharset;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Paths.get;

final class RenderContext extends STContext implements Closeable {

    // Used by Template
    final MavenProject project;
    final Log log;
    // Used by Group
    final String defaultEncoding;

    // Paths because File should have been deprecated in Java 1.7, to nag devs!
    final Path baseDir;
    final Path stSrcDir;
    final Path generatedSourcesJavaDir;
    //
    // Private stuff
    private final AtomicBoolean hasJavaFiles = new AtomicBoolean();
    static final Path GENERATED_SOURCES_JAVA
            = get("target", "generated-sources", "java");

    static RenderContext of(final RenderMojo mojo) throws MojoFailureException {
        final Path baseDir = mojo.project.getBasedir().toPath();
        return new RenderContext(mojo, baseDir, mojo.getLog());
    }

    private RenderContext(
            final RenderMojo mojo,
            final Path baseDir,
            final Log log) throws MojoFailureException {
        super(RenderContext.class.getClassLoader(),
                log::error);

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

        // Get default encoding of source and resource files, including .st and .stg files,
        // and provide a default value for rendered files too; not, should have been deprecated,,
        // FileWriter lameness.
        this.defaultEncoding = mojo.project.getProperties()
                .getProperty("project.build.sourceEncoding", defaultCharset().name());
        // Made use of optional plugin Dependencies feature to remove the need to explicitly build a ClassLoader,
        // instead get the classLoader of a plugin class.
        //
        this.project = mojo.project;
        this.log = log;
        this.baseDir = baseDir;
        this.generatedSourcesJavaDir = baseDir.resolve(GENERATED_SOURCES_JAVA);
    }

    // Use by Template to register generated .java files.
    void onGeneratedSourcesJavaFile() {
        // Only set once to avoid redundant costs
        if (hasJavaFiles.compareAndSet(false, true)) {
            synchronized (project) { // Just-in-case method not Thread-safe.
                project.addCompileSourceRoot(generatedSourcesJavaDir.toString());
            }
        }
    }
}
