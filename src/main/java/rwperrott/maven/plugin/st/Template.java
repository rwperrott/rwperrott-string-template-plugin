/*
 * See RenderMojo.java for license details/
 */

package rwperrott.maven.plugin.st;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.NoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STWriter;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.STMessage;
import rwperrott.stringtemplate.v4.STErrorConsumer;
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
import static java.nio.file.StandardOpenOption.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static org.stringtemplate.v4.misc.ErrorType.NO_SUCH_PROPERTY;
import static rwperrott.maven.plugin.st.UnicodeBOM.of;
import static rwperrott.maven.plugin.st.Utils.*;

/**
 * Where all the fun stuff happens.
 */
@SuppressWarnings("unused")
public final class Template implements STErrorConsumer, Callable<Void> {
    /**
     * The unique Template id, for logging.
     */
    @Parameter(required = true)
    public String id;
    /**
     * The id of a Group, which will provide an STGroup, to supply the named ST (compiled template) object for
     * rendering.
     */
    @Parameter(required = true)
    public String groupId;
    /**
     * The name of a template in an STGroup object, used to get it's ST object.
     */
    @Parameter(required = true)
    public String name;
    /**
     * If true, stop  when the first failure occurs in this template.
     * <p>
     * Default is false
     */
    @Parameter
    public boolean failFast = false;
    /**
     * A JSON serialised Map of attributes to be supplied to the template.
     * <p>
     * This is applied after any group provided attributes and entries with null value will remove that attribute name
     * from the ST.
     */
    @Parameter
    public String jsonAttributes;
    /**
     * Relative or absolute path of file to be rendered.
     * <p>
     * If target is a relative path If target ends in ".java" and doesn't start with ".", "src", or "target" then is
     * resolved as a child of "${project.basedir}/target/generated-sources/java" directory. Else is resolved as a child
     * of "${project.basedir}" directory.
     */
    @Parameter(required = true)
    public String target;
    /**
     * The charset name for encoding the template generated characters to bytes, to be written to the target file.  Bye,
     * bye damned FileWriter!
     * <p>
     * Default is ${project.build.sourceEncoding}, or the default charset name.
     */
    @Parameter(property = "project.build.sourceEncoding")
    public String targetEncoding;
    /**
     * If true, don't fail on a NO_SUCH_PROPERTY error.
     * <p>
     * Default is ${string-template.allowNoSuchProperty}
     */
    @SuppressWarnings("unused")
    @Parameter(property = "string-template.allowNoSuchProperty")
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
    @SuppressWarnings("CanBeFinal")
    @Parameter
    public TimeUnit timeoutUnit = SECONDS;

    /**
     * The duration for timeout of this.call()
     * <t>
     * Default is Long.MAX_VALUE
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter
    public long timeoutDuration = MAX_VALUE;

    /**
     * Only for transient storage of deserialized jsonAttributes.
     */
    @SuppressWarnings("unused")
    private transient Map<String, Object> attributes;
    private transient Path targetPath;
    private transient Charset targetCharset;
    private transient boolean isJava;
    private transient UnicodeBOM unicodeBOM;
    //
    private transient RenderMojo.Context ctx;
    private transient Group group;
    private transient ST st;
    private transient boolean failed;

    @Override
    public String toString() {
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
        ts.add("unicodeBOM", unicodeBOM);
        ts.add("allowNoSuchProperty", allowNoSuchProperty);
        ts.add("st", st);
        ts.complete();
        return ts.toString();
    }

    Path targetPath() {
        return targetPath;
    }

    private boolean failed() {
        failed = true;
        return failFast;
    }

    // synchronized this maybe required for thread-safety.
    void init(final RenderMojo.Context ctx, final Group group) throws Exception {
        // Deserialize JSON to a Map, then validate to ensure that all the map keys are Strings.
        if (jsonAttributes != null)
            attributes = readAndCheckJSONMap(jsonAttributes, "jsonAttributes", 0);

        { // Resolve and validate targetEncoding
            targetEncoding = ctx.resolveEncoding(targetEncoding);
            final UnicodeBOM bom = of(targetEncoding);
            if (null == bom)
                targetCharset = forName(targetEncoding);
            else {
                unicodeBOM = bom;
                targetCharset = bom.charset;
            }
        }

        targetPath = ctx.resolveTargetPath(target).normalize();
        isJava = ctx.isGeneratedSourcesJavaFile(targetPath);


        final Path targetDir = targetPath.getParent();
        final BasicFileAttributes targetDirAttributes = existsAttributes(targetDir);
        if (null == targetDirAttributes) {
            createDirectories(targetDir);
        } else {
            if (!targetDirAttributes.isDirectory())
                throw new FileNotFoundException(
                        format("parent of target '%s' is not a directory", target));
        }

        this.ctx = ctx;
        this.failFast |= group.failFast;
        this.group = group;
    }

    @Override
    @SuppressWarnings("UseSpecificCatch")
    public Void call() throws Exception {
        final Log log = ctx.log();

        // Get template
        final ST st;
        try {
            st = group.getST(name, attributes, this);
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
                if (withUnicodeBOM && null != unicodeBOM)
                    unicodeBOM.write(os);
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

        log.info(format("Render completed for Template id \"%s\"", id));

        if (isJava)
            ctx.onGeneratedSourcesJavaFile();

        return null;
    }

    @Override
    @SuppressWarnings({"UseSpecificCatch", "null"})
    public void accept(final String type, STMessage msg) {
        final RenderMojo.Context ctx = this.ctx;
        final Log log = ctx.log();
        try {
            msg = ctx.patch(msg, group.encoding);
        } catch (Exception e) {
            log.warn(e.getMessage(), msg.cause);
        }

        if (allowNoSuchProperty && NO_SUCH_PROPERTY == msg.error)
            log.warn(format("%s, for template id \"%s\", groupId \"%s\" name \"%s\"", msg, id, groupId, name));
        else {
            final String s = format("%s, for template id \"%s\", groupId \"%s\", name \"%s\"", msg, id, groupId, name);
            log.error(s, msg.cause);
        }
    }

    static final Pattern LINE_COLUMN = compile(" (\\d+):\\d+ ");
}
