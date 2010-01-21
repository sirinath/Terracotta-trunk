#
# All content copyright (c) 2003-2008 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#

module BundledComponents
  def bundled_components(name, directory, spec)
    add_binaries(spec)
    add_resources(name, directory)
    add_module_packages(spec)
    add_documentations(spec)
  end

  def add_resources(name, directory)
    @static_resources.supported_platforms(@build_environment).each do |platform|
      add_skeletons(@static_resources.skeletons_directory, name, platform, directory)
      if (@flavor !~ /opensource/i)
        if File.exist?(@static_resources.enterprise_skeletons_directory.to_s)
          add_skeletons(@static_resources.enterprise_skeletons_directory, name, platform, directory)
        end
      end
    end
  end

  def add_skeletons(skeldir, name, platform, directory)
    srcdir = FilePath.new(skeldir, name, platform).to_s
    if File.directory?(srcdir)
      destdir    = FilePath.new(product_directory, directory).ensure_directory
      ant.copy(:todir => destdir.to_s) do
        #ant.fileset(:dir => srcdir, :excludes => "**/.svn/**, **/.*, **/*/#{non_native.join(', **/*/')}")
        ant.fileset(:dir => srcdir, :excludes => "**/.svn/**, **/.*")
      end
    end
  end

  def add_binaries(component, libdir=libpath(component), destdir=libpath(component))
    runtime_classes_dir = FilePath.new(@distribution_results.build_dir, 'tmp').ensure_directory
    (component[:modules] || []).each do |a_module|
      name = a_module
      exclude_native_runtime_libraries = false
      exclude_runtime_libraries        = false
      kit_resources                    = []
      if a_module.instance_of?(Hash)
        name                             = a_module.keys.first
        exclude_native_runtime_libraries = a_module.values.first['exclude-native-runtime-libraries']
        exclude_runtime_libraries        = a_module.values.first['exclude-runtime-libraries']
        kit_resources                    = a_module.values.first['kit-resources'] || []
      end
      a_module = @module_set[name]
      a_module.subtree('src').copy_native_runtime_libraries(libdir, ant, @build_environment) unless exclude_native_runtime_libraries
      a_module.subtree('src').copy_runtime_libraries(libdir, ant)                            unless exclude_runtime_libraries

      kit_resource_files = kit_resources.join(',')
      a_module.subtree('src').copy_classes(@build_results, runtime_classes_dir, ant,
        :excludes => kit_resource_files)

      unless kit_resources.empty?
        kit_resources_dir = FilePath.new(destdir, 'resources').ensure_directory
        a_module.subtree('src').copy_classes(@build_results, kit_resources_dir, ant,
          :includes => kit_resource_files)
      end
    end

    jarfile = FilePath.new(destdir, "tc.jar")
    if File.exist?(libdir.to_s)
      ant.create_jar(jarfile, :basedir => runtime_classes_dir.to_s, :excludes => '**/build-data.txt') do
        libfiles  = Dir.entries(libdir.to_s).delete_if { |item| /\.jar$/ !~ item } << "resources/"
        ant.manifest do
          ant.attribute(:name => 'Class-Path', :value => libfiles.sort.join(' '))
          ant.attribute(:name => 'Main-Class', :value => 'com.tc.cli.CommandLineMain')
        end 
      end
    end
    runtime_classes_dir.delete
  end

  def add_dso_bootjar(component, destdir=libpath(component), build_anyway=false)
    bootjar_spec = component[:bootjar]
    unless bootjar_spec.nil?
      bootjar_dir = FilePath.new(File.dirname(destdir.to_s), *bootjar_spec['install_directory'].split('/')).ensure_directory
      if bootjar_spec['assert'].nil? || eval(bootjar_spec['assert']) || build_anyway
        bootjar_spec['compiler_versions'].each do |version|
          jvm = Registry[:jvm_set][version.to_s]
          if jvm
            puts("Building boot JAR with #{jvm}")
            bootjar = BootJar.new(jvm, bootjar_dir, @module_set, @static_resources.dso_boot_jar_config_file.to_s)
            bootjar.ensure_created(:delete_existing => true)
          else
            puts("Couldn't find suitable JVM for building boot JAR")
          end
        end
      end
    end
  end

  def add_module_packages(component, destdir=libpath(component))
    (component[:module_packages] || []).each do |module_package|
      runtime_classes_dir = FilePath.new(@distribution_results.build_dir, 'tmp').ensure_directory
      module_package.keys.each do |name|
        src      = module_package[name]['source'] || 'src'
        excludes = module_package[name]['filter'] || ''
        javadoc  = module_package[name]['javadoc']
        module_package[name]['modules'].each do |module_name|
          a_module = @module_set[module_name]
          a_module.subtree(src).copy_classes(@build_results, runtime_classes_dir, ant, :excludes => excludes)

          # also handling dependencies if set
          if module_package[name]['add_dependencies']
            puts "pacaking dependencies for #{a_module.name}"
            a_module.dependent_modules.each do |dependent_module|
              puts " .. #{dependent_module.name}"
              dependent_module.subtree(src).copy_classes(@build_results, runtime_classes_dir, ant, :excludes => excludes)
            end
          end
          
          if javadoc
            puts "Generating javadoc for #{a_module.name}"
            javadoc_destdir = FilePath.new(File.dirname(destdir.to_s), "platform", "docs", "javadoc").ensure_directory
            title = "Terracotta API version #{@config_source['api.version']}"
            ant.javadoc(:destdir => javadoc_destdir.to_s,
              :author => true, :version => true, :use => true, :defaultexcludes => "true",
              :header => title,
              :bottom => "<i>All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.</i>",
              :doctitle => title, :windowtitle => title) do
              ant.packageset(:dir => a_module.name + '/src', :defaultexcludes => true) do
                ant.include(:name => '**/**')
              end
            end
            puts "Done javadoc"
          end
        end
        libdir  = FilePath.new(File.dirname(destdir.to_s), *(module_package[name]['install_directory'] || '').split('/')).ensure_directory
        jarfile = FilePath.new(libdir, interpolate("#{name}.jar"))
        ant.create_jar(jarfile, :basedir => runtime_classes_dir.to_s, :excludes => '**/build-data.txt')
      end
      runtime_classes_dir.delete
    end
  end

  def add_documentations(component)
    documentations    = (component[:documentations] || {})
    install_directory = documentations.delete(:install_directory.to_s)
    documentations.keys.each do |document_set|
      document_set.each do |name|
        component[:documentations][name].each do |document|
          @static_resources.supported_documentation_formats.each do |format|
            srcfile = FilePath.new(@static_resources.documentations_directory, *(name.split('/') << "#{document}.#{format}")).to_s
            destdir = docspath(component, install_directory)
            case format
            when /html\.zip/
              # This has been moved to the new wiki
              # ant.unzip(:dest => destdir.ensure_directory.to_s, :src => srcfile)
            else
              ant.copy(:todir => destdir.ensure_directory.to_s, :file => srcfile)
            end if File.exist?(srcfile)
          end
        end
      end
    end
  end
end
