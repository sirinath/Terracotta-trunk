#
# All content copyright (c) 2003-2006 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#

module BundledModules
    def bundled_modules(name, directory, spec)
        # verify that the modules were compiled
        srcdir = @build_results.modules_home.to_s
        fail "The source directory for the modules: `#{srcdir}' does not exists" unless File.directory?(srcdir)

        # make sure all the modules listed in the manifest were built
        spec[:manifest].each { |pluginmodule|
          build_module = @module_set[pluginmodule]
          module_jar = @build_results.module_jar_file(build_module)
          fail "Unable to locate the jar file for the module #{pluginmodule}" unless File.exists?(module_jar.to_s)
        }

        # now copy everything over to the kits' modules directory
        destdir = FilePath.new(product_directory, directory).ensure_directory
        ant.copy(:todir => destdir.to_s) do
          ant.fileset(:dir => srcdir.to_s, :includes => "*.jar", :excludes => "**/.svn/**, **/.*")
        end
    end
end
