package rwperrott.maven.plugin.st;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Needed because JVM java.io developers were too lazy to add Unicode BOM support to InputStreamReader and
 * OutputStreamWriter, and other similar code!
 */
@SuppressWarnings("unused")
enum UnicodeBOM {
    UTF_8(StandardCharsets.UTF_8, 0xEF, 0xBB, 0xBF),
    UTF_16BE(StandardCharsets.UTF_16BE, 0xFE, 0xFF),
    UTF_16LE(StandardCharsets.UTF_16LE, 0xFF, 0xFE),
    UTF_32BE("UTF-32BE", 0x00, 0x00, 0xFE, 0xFF),
    UTF_32LE("UTF-32LE", 0xFF, 0xFE, 0x00, 0x00);
    // Used by Template.init()
    final Charset charset;
    // Used by Template.init()
    private final byte[] bytes;

    // Not in-lined to workaround parse exception bug in Maven
    // plugin org.codehaus.plexus:plexus-component-metadata:1.7.1.
    UnicodeBOM(final String charsetName, final int... a) {
        this(Charset.forName(charsetName), a);
    }

    UnicodeBOM(final Charset charset, final int... a) {
        this.charset = charset;
        int i = a.length;
        final byte[] bom = new byte[i];
        while (--i >= 0) {
            final int v = a[i];
            if (v < 0 || v > 0xFF)
                throw new IllegalArgumentException(
                        String.format("invalid byte value a[%d]:0x%x in %s", i, v, this));
            bom[i] = (byte) v;
        }
        this.bytes = bom;
    }

    void write(OutputStream os) throws IOException {
        os.write(bytes);
    }

    // Sadly STGroup is brittle, so we can't inject a wrapper creator
    // to detect Unicode BOM at the start of it's InputStreams.

    @Override
    public String toString() {
        return "UnicodeBOM{" +
               "name=" + name() +
               ", charset=" + charset +
               ", bytes=" + Arrays.toString(bytes) +
               '}';
    }

    //
    private static final Map<String, UnicodeBOM> MAP;

    static {
        final UnicodeBOM[] a = values();
        final Map<String, UnicodeBOM> map = new HashMap<>(a.length);
        for (UnicodeBOM e : a)
            map.put(e.charset.name(), e);
        MAP = map;
    }

    static UnicodeBOM of(String key) {
        return MAP.get(key);
    }

    static UnicodeBOM of(Charset key) {
        return MAP.get(key.name());
    }
}
