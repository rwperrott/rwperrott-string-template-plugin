/*
 * This substantially not Kevin Birch's work anymore.
 *
 * It's been hard work fixing all the bugs, re-architecting,
 * and replacing all the obsolete stuff, including for Maven 3.6.3.
 *
 * Copyright (c) 2020 Richard Perrott <github.rwp@hushmail.com>. All rights reserved.
 *
 * I haven't decided on a specific license yet, for now, explicit approval
 * is required for commercial forking and use.
 */

package rwperrott.maven.plugin.st;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.stringtemplate.v4.compiler.STException;
import rwperrott.stringtemplate.v4.STContext;
import rwperrott.stringtemplate.v4.STUtils;
import rwperrott.stringtemplate.v4.ToStringBuilder;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.nio.charset.Charset.defaultCharset;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Paths.get;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;
import static rwperrott.maven.plugin.st.Utils.selectThrow;

/**
 * Renders one or more String Templates, of a Group (string, file or directory), to specified target files, and adds any
 * .java files, written to target/generated-sourced, to project CompilerSourceRoot, to ensure that they will be
 * compiled.
 * <p>
 * If a reference AttributeRender or ModelAdapter classes are not found in the dependencies, the whole of src/main/java
 * will be compiled once, to attempt to make the class available, then the lookup retried, all later failed class
 * lookups will not be retried.  It is probably better for these classes to be provided via one, or more, "provided"
 * scope dependency, for user projects of this plugin.
 */
@SuppressWarnings("ALL")
@Mojo(name = "render", defaultPhase = GENERATE_SOURCES, threadSafe = true)
public final class RenderMojo extends AbstractMojo {
    @Parameter(property = "project.build.sourceEncoding")
    public String sourceEncoding = defaultCharset().name();
    /**
     * A relative directory path under "${project}/src/main", or an absolute directory path, to be used as the base
     * directory for template groups.
     * <p>
     * Default is "string-template" for "src/main/string-template";
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/string-template")
    public String templateSrcDir;
    /**
     * If true, stop when the first failure occurs for groups or templates.
     * <p>
     * Default is false
     */
    @Parameter(property = "string-template.failFast")
    public boolean failFast = false;

    /**
     * If true, render groups concurrently, using all the CPU cores.
     * <p>
     * Default is false
     */
    @Parameter(property = "string-template.renderGroupsConcurrently")
    public boolean renderGroupsConcurrently;

    /**
     * The array of groups for use by Templates.
     * <p>
     * At least one must be provided for Template use.
     */
    @Parameter(required = true)
    public Group[] groups;

    /**
     * The array of Templates to render.
     */
    @Parameter(required = true)
    public Template[] templates;

    /**
     * The Maven Project Object
     */
    @Parameter(property = "project", readonly = true)
    MavenProject project;

    /**
     * The Maven Session Object
     */
    @Parameter(property = "session", readonly = true)
    MavenSession session;

    private transient boolean failed;

    @Override
    public synchronized String toString() {
        final ToStringBuilder ts = new ToStringBuilder("RenderMojo", true);
        ts.add("project", project);
        ts.add("session", session);
        ts.add("groups", groups);
        ts.complete();
        return ts.toString();
    }

    /**
     * 1. Initilises and validate Groups.
     * <p>
     * 2. Initilises and validate Templates, and attaches them to the reference Group.
     * <p>
     * 3. Executes all the Groups, to execute their attached Templates, to get and render ST4 templates to target
     * files.
     * <p>
     * If any operation fails the Maven build should fail.
     */
    @Override
    @SuppressWarnings("UseSpecificCatch")
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Log log = getLog();
        try {
            try (final Context ctx = new Context(log)) { // Ensure that stStateMap saved
                // TODO: decide if will expose any options.
                ctx.allOptions();
                //
                final Map<String, Group> groupById = initGroups(ctx);
                initTemplates(ctx, groupById);
                final ExecutorService es = renderGroupsConcurrently
                                           ? Executors.newSingleThreadExecutor()
                                           : Executors.newWorkStealingPool();
                final int groupCount = groups.length;
                final List<Future<Void>> futures = new ArrayList<>(groupCount);
                int i = 0;
                try {
                    Stream.of(groups)
                          .map(es::submit)
                          .forEach(futures::add);
                    while (i < groupCount) {
                        final Group group = groups[i];
                        final Future<Void> future = futures.get(i);
                        try {
                            try {
                                future.get(group.timeoutDuration, group.timeoutUnit);
                            } catch (ExecutionException ex) {
                                throw selectThrow(ex);
                            }
                        } catch (STException e) {
                            throw e;
                        } catch (Throwable e) {
                            log.error(format("Render failed for %s (%s)", group, e.getMessage()), e);
                            if (failed())
                                break;
                        }
                        i++;
                    }
                } finally {
                    // Shutdown ExecutorService and cancel all added futures
                    es.shutdown();
                    final int n = futures.size();
                    while (i < n) {
                        futures.get(i++).cancel(true);
                    }
                }
                if (failed)
                    throw new MojoFailureException("Render Failed");
                log.info("All Groups rendered");
            }
        } catch (IOException e) {
            log.warn("Context close failed", e);
        }
    }

    private Map<String, Group> initGroups(final Context ctx) throws MojoExecutionException {
        final Log log = ctx.log();
        final int count = groups.length;
        final Map<String, Group> byId = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            final Group group = groups[i];

            if (null == group.id) {
                log.error(format("Group %s of %s has no id", i + 1, count));
                if (failed())
                    break;
                continue;
            }

            // Forbid duplicate id
            if (null != byId.put(group.id, group)) {
                log.error(format("Group %s of %s has a duplicate id \"%s\"",
                                 i + 1, count, group.id));
                if (failed())
                    break;
                continue;
            }

            // Init first to resolve field values, which are then validated here.
            try {
                group.init(ctx);
            } catch (Exception e) {
                log.error(format("Failed to initialise%n%s", group), e);
                if (failed())
                    break;
                continue;
            }

            if (log.isDebugEnabled())
                log.debug("initialised " + group);
        }
        if (failed)
            throw new MojoExecutionException("Some Groups had invalid property values");
        //
        return byId;
    }

    private void initTemplates(final Context ctx, final Map<String, Group> groupById) throws MojoExecutionException {
        final Log log = ctx.log();
        boolean failed = false;
        final int count = templates.length;
        final Map<String, Template> byId = new HashMap<>(count);
        final Map<Path, Template> byTarget = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            final Template template = templates[i];

            if (null == template.id) {
                log.error(format("Template %s of %s has no id", i + 1, count));
                if (failed())
                    break;
                continue;
            }

            // Forbid duplicate id
            if (null != byId.put(template.id, template)) {
                log.error(format("Template %s of %s has a duplicate id \"%s\"",
                                 i + 1, count, template.id));
                if (failed())
                    break;
                continue;
            }

            // Forbid missing Group id
            final Group group = groupById.get(template.groupId);
            if (null == group) {
                log.error(format("Template id \"%s\" references undefined Group id \"%s\"",
                                 template.id, template.groupId));
                if (failed())
                    break;
                continue;
            }

            // Init first to resolve field values, which are then validated here.
            try {
                template.init(ctx, group);
            } catch (Exception e) {
                log.error(format("Failed to initialise%n%s", template), e);
                if (failed())
                    break;
                continue;
            }

            // Forbid duplicate targetPath
            final Path targetPath = template.targetPath();
            Template prior = byTarget.put(targetPath, template);
            if (null != prior) {
                log.error(format("Template id \"%s\" has same effective target \"%s\" (targetPath \"%s\") as Template id \"%s\" target \"%s\"",
                                 template.id, template.target, targetPath,
                                 prior.id, prior.target));
                if (failed())
                    break;
                continue;
            }

            // Add to group, all templates for an STGroup can use a transient one.
            group.templates.add(template);
            if (log.isDebugEnabled())
                log.debug("initialised " + template);
        }
        if (failed)
            throw new MojoExecutionException("Some Templates had invalid property values");
    }

    private boolean failed() {
        failed = true;
        return failFast;
    }

    final class Context extends STContext implements Closeable {

        private final Log log;
        private final Path baseDir;
        private final Path stSrcDir;
        private final Path generatedSourcesJavaDir;

        //
        // Private stuff
        private final AtomicBoolean hasJavaFiles = new AtomicBoolean();

        private Context(final Log log) throws MojoFailureException {
            super();
            //
            final Path baseDir = project.getBasedir().toPath().toAbsolutePath();
            final Path templateSrcDir = get(RenderMojo.this.templateSrcDir);
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
            this.generatedSourcesJavaDir = baseDir.resolve(Utils.GENERATED_SOURCES_JAVA);
        }

        Log log() {
            return log;
        }

        Path resolveTargetPath(String target) {
            Path targetPath = get(target);
            if (targetPath.isAbsolute())
                return targetPath;

            if (Utils.isJavaFile(targetPath))
                switch (targetPath.getName(0).toString()) {
                    case ".":
                    case "src":
                    case "target":
                        break;
                    default:
                        if (!targetPath.startsWith(Utils.GENERATED_SOURCES_JAVA)) {
                            return generatedSourcesJavaDir.resolve(targetPath);
                        }
                        break;
                }

            return baseDir.resolve(targetPath);
        }

        boolean isGeneratedSourcesJavaFile(Path path) {
            return path.startsWith(generatedSourcesJavaDir) && Utils.isJavaFile(path);
        }

        boolean failFast() {
            return failFast;
        }

        String resolveEncoding(String encoding) {
            return null == encoding ? sourceEncoding : encoding.toUpperCase(Locale.ROOT);
        }

        STUtils.TypeAndURL resolveTypeAndURL(final String source) throws IOException {
            return STUtils.resolveTypeAndURL(source, stSrcDir);
        }

        // Use by Template to register generated .java files.
        void onGeneratedSourcesJavaFile() {
            // Only set once to avoid redundant costs
            if (hasJavaFiles.compareAndSet(false, true)) {
                final MavenProject project = RenderMojo.this.project;
                synchronized (project) { // Just-in-case method not Thread-safe.
                    project.addCompileSourceRoot(generatedSourcesJavaDir.toString());
                }
            }
        }
    }
}
