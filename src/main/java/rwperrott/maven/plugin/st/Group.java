/*
 * See RenderMojo.java for license details/
 */
package rwperrott.maven.plugin.st;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STErrorListener;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.STMessage;
import rwperrott.stringtemplate.v4.STErrorConsumer;
import rwperrott.stringtemplate.v4.STGroupType;
import rwperrott.stringtemplate.v4.STUtils;
import rwperrott.stringtemplate.v4.ToStringBuilder;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static java.lang.Long.MAX_VALUE;
import static java.lang.String.format;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.Executors.newWorkStealingPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static rwperrott.maven.plugin.st.Utils.selectThrow;
import static rwperrott.stringtemplate.v4.STUtils.resolveTypeAndURL;

public final class Group implements STErrorConsumer, Callable<Void> {
    /**
     * The unique Group id, can be referenced groupId of Templates.
     */
    @SuppressWarnings("unused")
    @Parameter(property = "id", required = true)
    public String id;
    /**
     * A string expression, of one or more string template, a .stg file path, or a directory path.
     * <p>
     * Useful if you want a different relative or absolute path, than the default of src/main/string-template for a
     * string, or different directory or file, at a URL or in local filesystem.
     * <p>
     * A STGroupString will be used if source containing "::=" and can contain multiple templates. The template(s) text
     * should be wrapped like
     * <code></code><![CDATA[atemplate() ::= <%same-linet%> or << multiple-lines
     * >>]</code>, to avoid having to use XML escaping for <code><</code> and
     * <code>></code>.
     * <p>
     * An STGroupFile will be used if source doesn't contain "::=" and ends in ".stg". STGroupFile only supports ".stg"
     * files.
     * <p>
     * An STGroupDir will be used if source doesn't contain "::=" and doesn't end in ".stg". STGroupDir supports ".st"
     * and ".stg" files.
     * <p>
     * If source is not a valid URL, it will the converted to a file then converted to a URL.
     * <p>
     * Default is ${string-template.source}
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(defaultValue ="${string-template.source}")
    public String source = DEFAULT_DIR;
    /**
     * The name of the charset encoding of any .st or .stg files, if different from the project source encoding.
     * <p>
     * Default is ${project.build.sourceEncoding}, or the default charset name.
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}")
    public String encoding;
    /**
     * A map of type name to AttributeRenders class name; key = name of Class object to be rendered, value = name of
     * AttributeRender class to have an instance registered.
     *
     * @see <a href="https://www.stringtemplate.org/api/org/stringtemplate/v4/AttributeRenderer.html">AttributeRenderer</a>
     * @see <a href="https://github.com/antlr/stringtemplate4/blob/master/doc/renderers.md" target="_blank"
     *         >Attribute Renderers</a>
     */
    @SuppressWarnings("unused")
    @Parameter
    public Map<String, String> attributeRenderers;
    /**
     * A map of type name to ModelAdapter class name; key = name of Class object to be adapted, value = name of
     * ModelAdapter class to have an instance registered.
     *
     * @see <a href="https://www.stringtemplate.org/api/org/stringtemplate/v4/ModelAdapter.html">ModelAdapter</a>
     * @see <a href="https://www.stringtemplate.org/api/org/stringtemplate/v4/ModelAdaptor.html" target="_blank"
     *         >Model Adapters</a>
     */
    @SuppressWarnings("unused")
    @Parameter
    public Map<String, String> modelAdaptors;
    /**
     * If true, stop rendering Templates when the first fails or timeouts.
     * <p>
     * Default is false
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(defaultValue = "${string-template.failFast}")
    public boolean failFast = false;
    /**
     * If true, render templates concurrently, using all the CPU cores.
     * <p>
     * Default is false
     * Disabled until ST concurrency bugs in ST4.3.1 are fixed.
     */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    //@Parameter(defaultValue = "${string-template.renderTemplatesConcurrently}")
    private final boolean renderTemplatesConcurrently = false;
    /**
     * A java.util.concurrent.TimeUnit for timeout of this.call().
     * <t>
     * Default is TimeUnit.SECONDS
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter
    public TimeUnit timeoutUnit = SECONDS;
    /**
     * The duration for timeout of this.call().
     * <t>
     * Default is Long.MAX_VALUE
     */
    @SuppressWarnings("CanBeFinal")
    public long timeoutDuration = MAX_VALUE;
    /**
     * The array of templates to render, for this group.
     */
    @SuppressWarnings("CanBeFinal")
    List<Template> templates = new ArrayList<>();

    //
    // Transient variables
    //
    transient STGroupType type;
    transient STGroup stGroup;
    transient RenderContext ctx;
    private URL url;
    private boolean failed;

    @Override
    public synchronized String toString() {
        final ToStringBuilder ts = new ToStringBuilder("Group", true);
        //
        ts.add("id", id);
        ts.add("type", type);
        ts.add("source", source);
        ts.add("url", url);
        ts.add("encoding", encoding);
        ts.add("attributeRenderers", attributeRenderers);
        ts.add("modelAdapters", modelAdaptors);
        ts.add("failFast", failFast);
        //ts.add("renderTemplatesConcurrently", renderTemplatesConcurrently);
        ts.add("timeoutUnit", timeoutUnit);
        ts.add("timeoutDuration", timeoutDuration);
        ts.add("stGroup", stGroup);
        ts.complete();
        return ts.toString();
    }

    /**
     * validate and initialise for possible concurrency.
     */
    @SuppressWarnings("UseSpecificCatch")
    void init(final RenderContext env) {
        this.ctx = env;

        if (null == encoding)
            encoding = env.defaultEncoding;

        if (null == source) {
            throw new IllegalArgumentException("source is null");
        }
        if (source.trim().isEmpty()) {
            throw new IllegalArgumentException("source is blank");
        }

        // Resolve type of source, and resolve relative paths with a default directory,
        // something which StringTemplate should do itself!
        try {
            final STUtils.TypeAndURL typeAndDir
                    = resolveTypeAndURL(source, env.stSrcDir);
            this.type = typeAndDir.type;
            this.url = typeAndDir.url;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    format("Invalid source \"%s\" (%s)", source, e.getMessage()), e);
        }
    }

    @SuppressWarnings("SynchronizeOnNonFinalField")
    ST getST(final String name, final Map<String, Object> attributes, final STErrorListener listener) {
        // Not thread safe because getInstance doesn't accept a listener, so have to lock.
        synchronized (stGroup) {
            stGroup.setListener(listener);
            final ST st = stGroup.getInstanceOf(name);
            if (null == st)
                throw new IllegalStateException("null ST returned");
            if (null != attributes)
                attributes.forEach(st::add);
            return st;
        }
    }

    @SuppressWarnings({"UseSpecificCatch", "ThrowableResultIgnored", "SynchronizationOnLocalVariableOrMethodParameter"})
    @Override
    public Void call() {
        final RenderContext env = this.ctx;
        final Log log = env.log;

        // Create and load STGroup
        final STGroup stGroup;
        try {
            stGroup = type.getSTGroup(id, source, url, encoding);
            synchronized (stGroup) {
                stGroup.setListener(this); // Detect bug of StringTemplate not always throwing an exception for a load error
                stGroup.load();
            }
        } catch (Exception e) {
            throw new IllegalStateException(format("failed to create an %s (%s)",
                                                   type.stGroupClass.getSimpleName(), e.getMessage()), e);
        }
        // Handle bug of StringTemplate not always throwing an exception for a load error
        if (failed) {
            throw new IllegalStateException("failed to fully create an " + type.stGroupClass.getSimpleName());
        }

        log.info(format("Group id \"%s\" created an %s \"%s\"",
                        id, type.stGroupClass.getSimpleName(), type.getSource(stGroup)));
        //
        synchronized (stGroup) {
            env.registerRenderers(stGroup, attributeRenderers);
            env.registerModelAdaptors(stGroup, modelAdaptors);
        }
        this.stGroup = stGroup;

        final ExecutorService es = renderTemplatesConcurrently
                                   ? newWorkStealingPool()
                                   : newSingleThreadExecutor();
        final int templateCount = templates.size();
        final List<Future<Void>> futures = new ArrayList<>(templateCount);
        int i = 0;
        try {
            templates.stream()
                     .map(es::submit)
                     .forEach(futures::add);
            while (i < templateCount) {
                final Template template = templates.get(i);
                final Future<Void> future = futures.get(i);
                try {
                    try {
                        future.get(template.timeoutDuration, template.timeoutUnit);
                    } catch (ExecutionException ex) {
                        throw selectThrow(ex);
                    }
                } catch (Throwable e) {
                    failed = true;
                    log.error(format("Render failed for %s (%s)", template, e.getMessage()), e);
                    if (failFast) {
                        break;
                    }
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
        if (failed) {
            throw new STException("Some Templates failed to render using "+this, null);
        }

        log.info(format("Render completed for Group id \"%s\"", id));

        return null;
    }

    @Override
    public void accept(String type, STMessage msg) {
        ctx.log.error(format("%s, for group id '%s'", msg, id), msg.cause);
        failed = true;
    }
    static final String DEFAULT_DIR = ".";
}
