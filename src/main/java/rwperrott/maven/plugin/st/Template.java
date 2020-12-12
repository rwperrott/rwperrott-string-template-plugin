/*
 * See RenderMojo.java for license details/
 */

package rwperrott.maven.plugin.st;

import rwperrott.stringtemplate.v4.STErrorConsumer;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.stringtemplate.v4.*;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.STMessage;
import rwperrott.stringtemplate.v4.ToStringBuilder;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.lang.Long.MAX_VALUE;
import static java.lang.String.format;
import static java.nio.charset.Charset.forName;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Paths.get;
import static java.nio.file.StandardOpenOption.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.*;
import static org.stringtemplate.v4.misc.ErrorType.NO_SUCH_PROPERTY;
import static rwperrott.maven.plugin.st.UnicodeBOM.of;
import static rwperrott.maven.plugin.st.Utils.*;

/**
 * Where all the fun stuff happens.
 */
@SuppressWarnings("ALL")
public final class Template implements STErrorConsumer, Callable<Void> {
    /**
     * The unique Template id, for logging.
     */
    @Parameter(property = "id", required = true)
    public String id;
    /**
     * The id of a Group, which will provide an STGroup, to supply the named ST (compiled template) object for
     * rendering.
     */
    @Parameter(property = "groupId", required = true)
    public String groupId;
    /**
     * The name of a template in an STGroup object, used to get it's ST object.
     */
    @Parameter(property = "name", required = true)
    public String name;
    /**
     * A JSON serialised Map of attributes to be supplied to the template.
     */
    @Parameter(property = "jsonAttributes")
    public String jsonAttributes;
    /**
     * This has to be a String, to stop the freaking annoying Maven behaviour of turning a relative file name into an
     * absolute filename in ${basedir}, hmmm!
     * <p>
     * The path to the output file, can be a relative or absolute path.
     * <p>
     * I can't use Path, because there doesn't appear to be support for Path Mojo properties.
     * <p>
     * If a relative path and name ends in .java, then will treated as a path in directory
     * "${basedir}/target/generated-sources/java", unless path starts with "target/generated-sources/java".
     * <p>
     * Else if a relative path and name doesn't end in .java, then will be a path in directory "${basedir}"
     */
    @Parameter(required = true)
    public String target;
    /**
     * The charset name for encoding the template generated characters to bytes, to be written to the target file.  Bye,
     * bye damned FileWriter!
     * <p>
     * Default is ${project.build.sourceEncoding}, or the default charset name.
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}")
    public String targetEncoding;
    /**
     * If true, don't fail on a NO_SUCH_PROPERTY error.
     * <p>
     * Default is ${string-template.allowNoSuchProperty}
     */
    @Parameter(defaultValue = "${string-template.allowNoSuchProperty}")
    public boolean allowNoSuchProperty;
    /**
     * If true add Unicode BOM bytes at start of target file
     * <p>
     * Default is false
     */
    @Parameter
    public boolean withUnicodeBOM;

    //
    // Transient variables
    //
    /**
     * If false don't appendMap render text
     * <p>
     * Default is true
     */
    @Parameter
    public boolean autoIndent = true;

    /**
     * A java.util.concurrent.TimeUnit for timeout of this.call()
     * <t>
     * Default is TimeUnit.SECONDS
     */
    @Parameter
    public TimeUnit timeoutUnit = SECONDS;

    /**
     * The duration for timeout of this.call()
     * <t>
     * Default is Long.MAX_VALUE
     */
    @Parameter
    public long timeoutDuration = MAX_VALUE;

    /**
     * Only for transient storage of deserialized jsonAttributes.
     */
    transient Path targetPath;
    private transient Map<String, Object> attributes;
    private transient Log log;
    private transient Charset targetCharset;
    private transient boolean isJava;
    private transient byte[] bomBytes;
    private transient Group group;
    private transient ST st;
    private transient boolean failed;

    @Override
    public synchronized String toString() {
        final ToStringBuilder ts = new ToStringBuilder("Template", true);
        ts.add("id", id);
        ts.add("groupId", groupId);
        ts.add("name", name);
        ts.add("jsonAttributes", jsonAttributes);
        ts.add("target", target);
        ts.add("targetPath", targetPath);
        ts.add("targetEncoding", targetEncoding);
        ts.add("allowNoSuchProperty", allowNoSuchProperty);
        ts.add("withUnicodeBOM", withUnicodeBOM);
        ts.add("autoIndent", autoIndent);
        ts.add("timeoutUnit", timeoutUnit);
        ts.add("timeoutDuration", timeoutDuration);
        ts.add("isJava", isJava);
        ts.add("bomBytes", bomBytes);
        ts.add("allowNoSuchProperty", allowNoSuchProperty);
        ts.add("st", st);
        ts.complete();
        return ts.toString();
    }

    // synchronized this maybe required for thread-safety.
    void init(Group group) throws Exception {
        this.group = group;

        final RenderContext env = group.ctx;
        // Deserialize JSON to a Map, then validate to ensure that all the map keys are Strings.
        if (jsonAttributes != null) {
            final Map<?, ?> map = jsonMappers.get().readValue(jsonAttributes, Map.class);
            map.forEach((k, v) -> {
                if (k.getClass() != String.class)
                    throw new IllegalArgumentException(
                            format("non-String key %s:%s in jsonAttributes '%s'",
                                   k.getClass().getName(), k.toString(), jsonAttributes));
            });
            attributes = (Map<String, Object>) map;
        }

        if (null == targetEncoding)
            targetEncoding = env.defaultEncoding;

        final UnicodeBOM bom = of(targetEncoding);
        if (null == bom) {
            if (withUnicodeBOM)
                throw new IllegalStateException(format("targetEncoding '%s' is not supported for Unicode BOM",
                                                       targetEncoding));
            targetCharset = forName(targetEncoding);
        } else {
            bomBytes = bom.bytes;
            targetCharset = bom.charset;
        }

        targetPath = get(target);
        // Resolve target to an absolute Path, and detect java files in ${basedir}/target/generated-sources/java directory
        if (targetPath.isAbsolute()) {
            if (targetPath.startsWith(env.generatedSourcesJavaDir) && isJavaFile(targetPath))
                isJava = true;
        } else {
            Path relativeTargetPath = targetPath;
            if (isJavaFile(relativeTargetPath)) {
                isJava = true;
                if (relativeTargetPath.startsWith(RenderContext.GENERATED_SOURCES_JAVA))
                    targetPath = env.baseDir.resolve(relativeTargetPath);
                else
                    targetPath = env.generatedSourcesJavaDir.resolve(relativeTargetPath);
            } else
                targetPath = env.baseDir.resolve(relativeTargetPath);
        }

        targetPath = targetPath.normalize().toAbsolutePath();

        final Path targetDir = targetPath.getParent();
        final BasicFileAttributes targetDirAttributes = existsAttributes(targetDir);
        if (null == targetDirAttributes) {
            createDirectories(targetDir);
        } else {
            if (!targetDirAttributes.isDirectory())
                throw new FileNotFoundException(
                        format("parent of target '%s' is not a directory", target));
        }

        this.log = env.log;
    }


    @Override
    @SuppressWarnings("UseSpecificCatch")
    public Void call() throws Exception {
        // Get template
        final ST st;
        try {
            st = group.getST(name,attributes,this);
        } catch (Exception e) {
            throw new STException("failed to get ST instance for " + this, e);
        }
        this.st = st;

        Files.deleteIfExists(targetPath);

        // Render template
        try {
            // Render to existing targetPathTmp.
            final Path targetPathTmp = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
            try (OutputStream os = newOutputStream(targetPathTmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
                if (withUnicodeBOM)
                    os.write(bomBytes);
                final Writer w = new OutputStreamWriter(os, targetCharset);

                    // Must provide listener to writer, because don't want it to use STGroup one when concurrent use stops
                // causing errors.
                final STWriter stWriter = autoIndent
                                          ? new AutoIndentWriter(w)
                                          : new NoIndentWriter(w);
                // Lock st, because can't find a way to make it Thread-safe
                st.write(stWriter, this); // Use own listener, because STGroup one must be locked to use it's one.
                w.flush();
            }
            move(targetPathTmp, targetPath);
        } catch (Exception e) {
            throw new STException("render failed for " + this, e);
        }
        if (failed)
            throw new STException("render failed for " + this, null);

        log.info(format("Render completed for Template id \"%s\"",id));

        if (isJava)
            group.ctx.onGeneratedSourcesJavaFile();

        return null;
    }

    @Override
    @SuppressWarnings({"UseSpecificCatch", "null"})
    public void accept(final String type, STMessage msg) {
        try {
            msg = group.ctx.patch(msg, group.encoding);
        } catch(Exception e) {
           log.warn(e.getMessage(), msg.cause);
        }

        if (allowNoSuchProperty && NO_SUCH_PROPERTY == msg.error)
            log.warn(format("%s, for template id \"%s\", groupId \"%s\" name \"%s\"", msg, id, groupId, name));
        else {
            log.error(format("%s, for template id \"%s\", groupId \"%s\", name \"%s\"", msg, id, groupId, name));
            failed = true;
        }
    }

    static final Pattern LINE_COLUMN = compile(" (\\d+):\\d+ ");
}
