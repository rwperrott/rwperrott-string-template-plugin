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

See [Usage](Usage.md) for a template POM.

I doubt that I'll provide "site" code because it's ra