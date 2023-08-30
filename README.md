# rwperrott-string-template-plugin
A Maven plugin for running [StringTemplate](http://www.stringtemplate.org/) on strings, files, or directories of files.

The plugin is built for Java 1.8, because this is the minimum updated version, because 
I need the functional features in it, and Jackson library requires it too.

## Base options
- The "templateSrcDir" can optionally be specified, with the default being "/src/main/string-template".
## Group options
- Encoding of input files, for STGroupFile and STGroupDir, defaults to Maven source encoding, with optional override.
- Groups all have unique ids, for reference by templates, and to simplify logging messages.
- STGroupString (this needs a CData wrapper), STGroupFile and STGroup files are all supported.
- Registration of AttributeRenders and ModelAdapters on STGroups is supported. 
- The plugin optionally allows concurrent running of groups, with optional timeout.
## Template options
- Templates all have ids to simplify logging messages.
- Attributes can optionally be provided via a "jsonAttributes" JSON Map property on templates.
- AutoIndenting can be disabled for template rendering.
- Unicode BOMs can optionally be written at the start of rendered files. 
- Encoding of rendered files defaults to Maven source encoding, with override for optional output.
- If any Java files is written in "target/generated-sources" directory, the "target/generated-sources" directory
  is automatically added to the compiler sources list
- Template supports optional timeout, but no concurrency, due to lack of action on reported Thread-safety bug,
  in STGroup and ST, for about 6 years ago!
## StringTemplate Patches
- I also consider relative template line numbers in rendering error messages a stupid and annoying bug,
  so convert these to absolute line number via my [rwperrott-string-template-utils](https://github.com/rwperrott/rwperrott-string-template-utils) library.

### Maven
```xml
<dependency>
  <groupId>com.github.rwperrott</groupId>
  <artifactId>rwperrott-string-template-utils</artifactId>
  <version>2.2.3</version>
</dependency>
```

The jars could be requested using jitpack.io.
Jetpack does not appear to provide an index, at least for free use, so you probably won't see any coordinate hints in
your IDE.
```xml
<repository>
  <id>jitpack.io</id>
  <url>https://jitpack.io</url>
</repository>
```

### Usage
A configuration template is as follows:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>rwperrott.maven.plugins</groupId>
            <artifactId>rwperrott-string-template-plugin</artifactId>
            <version>2.2.3</version>
            <dependencies> <!-- optional -->
                <!-- dependencies for a library providing more AttributeRenderers or ModelAdapters -->
            </dependencies>
            <executions>
                <execution> <!-- default phase is `generate-sources` -->
                    <goals>
                        <goal>render</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <templateSrcDir>Optional: a relative or absolute base directory for STGroupFiles and STGroupDirs
                                Default: ${project.basedir}/src/main/string-template</templateSrcDir>
                <failFast>Optional: if true, stop when the first failure or timeouts occurs</failFast>
                <renderGroupsConcurrently>Optional: if true, render groups concurrently, using all the CPU cores</renderGroupsConcurrently>
                <groups>
                    <group>
                        <id>Required: unique id of group</id>
                        <source>Required: A string expression, in CDATA section, url/path for a directory, or url/path for a .stg file</source>
                        <encoding>Optional: override default source encoding charset name</encoding>
                        <failFast>Optional: if true, stop when first failure or timeouts occurs.</failFast>
                        <attributeRenderers><!-- Optional: a map of AttributeRenderers to register on the STGroup -->
                            <class_name_of_type>class name of an AttributeRenderer implementation</class_name_of_type>
                            <!-- simple names are allowed for java.lang classes e.g. -->
                            <String>org.stringtemplate.v4.StringRenderer</String>
                        </attributeRenderers>
                        <modelAdapters><!-- Optional: a map of ModelAdapters to register on the STGroup -->
                            <class_name_of_type>class name of an ModelAdaptor implementation</class_name_of_type>
                            <!-- simple names are allowed for java.lang classes e.g. -->
                            <String>rwperrott.stringtemplate.v4.StringInvokeAdaptor</String>
                        </modelAdapters>
                        <timeoutUnit>Optional: A java.util.concurrent.TimeUnit for timeout of this.call().</timeoutUnit>
                        <timeoutDuration>Optional: The duration for timeout of this.call()</timeoutDuration>
                    </group>
                </groups>
                <templates>
                    <template>
                        <id>Required: unique id of template</id>
                        <groupId>Required: id of a group</groupId>
                        <name>Required: name of a StringTemplate template</name>
                        <failFast>Optional: if true, stop when first failure occurs.</failFast>
                        <jsonAttributes>Optional: JSON map of attributes, in a possible inside a CDATA clause</jsonAttributes>
                        <target>Required: relative or absolute file path, java paths can be just the slashed full package name .java</target>
                        <targetEncoding>Optional: override default source encoding charset name</targetEncoding>
                        <allowNoSuchProperty>Optional: if false, fail for a NO_SUCH_PROPERTY error</allowNoSuchProperty>
                        <withUnicodeBOM>Optional: if true add Unicode BOM bytes at start of target file</withUnicodeBOM>
                        <autoIndent>Optional: if false don't indent render text</autoIndent>
                        <timeoutUnit>Optional: A java.util.concurrent.TimeUnit for timeout of this.call().</timeoutUnit>
                        <timeoutDuration>Optional: The duration for timeout of this.call()</timeoutDuration>
                    </template>
                </templates>
            </configuration>
        </plugin>
    </plugins>
</build>
```
* Any element, like "source" or "jsonAttributes" with a value containing any XML reserved characters,
  is more readable and maintainable wrapped in a CDATA clause, that using XML escaping.
  
e.g. a String template:
```xml
<source><![CDATA[
hello(someone) ::= <<
hello <someone>
>>
]]></source>
```
e.g. tricky JSON:
```xml
<jsonAttributes><![CDATA[
{
   "lessThan" : "<",
   "moreThan" : ">",
}
]]></jsonAttributes>
```

