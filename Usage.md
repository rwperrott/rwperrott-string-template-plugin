# Usage

The configuration template is as follows:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>rwperrott</groupId>
            <artifactId>string-template-maven-plugin</artifactId>
            <version>2.2.0</version>
            <dependencies> <!-- optional -->
                <!-- dependencies for library providing extra AttributeRenderers or ModelAdapters -->
            </dependencies>
            <executions>
                <execution> <!-- default phase is `generate-sources` -->
                    <goals>
                        <goal>render</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <templateSrcDir>Optional: a relative or absolute base directory for STGroupFiles and STGroupDirs</templateSrcDir>
                <failFast>Optional: if true, stop rendering Groups when the first Group fails or timeouts</failFast>
                <renderGroupsConcurrently>Optional: if true, render groups concurrently, using all the CPU cores</renderGroupsConcurrently>
                <groups>
                    <group>
                        <id>Required: unique id of group</name>
                        <source>Required: A string expression, in CDATA section, url/path for a directory, or url/path for a .stg file</source>
                        <encoding>Optional: override default source encoding charset name</encoding>
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
                        <failFast>Optional: if true, stop rendering Templates when the first fails or timeouts.</failFast>
                        <timeoutUnit>Optional: A java.util.concurrent.TimeUnit for timeout of this.call().</timeoutUnit>
                        <timeoutDuration>Optional: The duration for timeout of this.call()</timeoutDuration>
                    </group>
                </groups>
                <template>
                    <template>
                        <id>Required: unique id of template</id>
                        <groupId>Required: id of a group</groupId>
                        <name>Required: name of a StringTemplate template</name>
                        <jsonAttributes>Optional: JSON map of attributes</jsonAttributes>
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

See tests projects for working Group and Template examples.
