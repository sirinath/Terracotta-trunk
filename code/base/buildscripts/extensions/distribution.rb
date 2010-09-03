#
# All content copyright (c) 2003-2008 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#

class BaseCodeTerracottaBuilder <  TerracottaBuilder
  include MavenConstants
  include BuildData

  # assemble the kit for the product code supplied
  def dist(product_code='DSO', flavor='OPENSOURCE')
    check_if_type_supplied(product_code, flavor)

    depends :init, :compile
    call_actions :__assemble
  end

  # Assemble a kit just like 'dist', but then selectively extract elements from
  # the kit and bundle into a patch tarball.
  def patch(product_code = 'DSO', flavor = 'OPENSOURCE')
    @no_demo = true
    $patch = true
    
    # Do error checking first, before going through the lengthy dist target
    descriptor_file = self.patch_descriptor_file.to_s
    unless File.readable?(descriptor_file)
      raise("Patch descriptor file does not exist: #{descriptor_file}")
    end

    patch_descriptor = YAML.load_file(descriptor_file)
    unless patch_descriptor['level'] && patch_descriptor['files'].is_a?(Array)
      raise("Invalid patch descriptor file")
    end

    patch_level = config_source['level'] || patch_descriptor['level']
    $XXX_patch_level = patch_level

    if config_source[MAVEN_REPO_CONFIG_KEY]
      @internal_config_source[MAVEN_CLASSIFIER_CONFIG_KEY] = "patch#{patch_level}"
    end

    dist(product_code, flavor)

    begin
      # Warn if patch level is not a positive integer
      i = Integer(patch_level)
      raise("not a positive integer") if i < 0
    rescue
      loud_message("WARNING: patch level is not a positive integer")
    end

    puts("Building patch level #{patch_level}")

    # executables will get different permissions in patch tar
    patch_files = Array.new
    patch_bin_files = Array.new

    Dir.chdir(product_directory.to_s) do
      # Make sure patch-data.txt and RELEASE-NOTES.txt are included in the patch
      patch_files << create_patch_data(patch_level, @config_source, FilePath.new('lib', 'resources'))
      patch_files << 'RELEASE-NOTES.txt'

      patch_descriptor['files'].each do |file|
        raise("Patch may not include build-data.txt") if file =~ /build-data\.txt$/

        path = file.to_s
        unless File.readable?(path)
          raise("Patch descriptor file references non-existant file: #{path}")
        end

        if file =~ /\.sh$|\.bat$|\.exe$|\.dll$|\/bin\/|\/libexec\//
          patch_bin_files << path
        else 
          patch_files << path
        end
      end

      patch_file_name = File.basename(Dir.pwd) + "-patch-#{patch_level}.tar.gz"
      patch_file = FilePath.new(self.dist_directory, patch_file_name)
      ant.tar(:destfile => patch_file.to_s, :compression => 'gzip', :longfile => 'gnu') do
        ant.tarfileset(:dir => Dir.pwd, :includes => patch_files.join(','))
        unless patch_bin_files.empty?
          ant.tarfileset(:dir => Dir.pwd,
            :includes => patch_bin_files.join(','),
            :mode => 755)
        end
      end
      
      if config_source['target']
        ant.copy(:file => patch_file.to_s, :todir => config_source['target'], :verbose => true)
      end
      
    end
  end

  def dist_all(flavor='OPENSOURCE')
    @flavor = flavor.downcase
    depends :init, :compile
    product_definition_files(flavor).each do |product_definition_file|
      @product_code = product_code(product_definition_file)
      @flavor       = flavor
      call_actions :__assemble
      srcdir  = product_directory.to_s
      destdir = FilePath.new(@distribution_results.archive_dir.ensure_directory, package_filename).to_s
      ant.move(:file => srcdir, :todir => destdir)
    end
  end

  def dist_maven(flavor = 'OPENSOURCE')
    unless config_source[MAVEN_REPO_CONFIG_KEY]
      @internal_config_source[MAVEN_REPO_CONFIG_KEY] = MAVEN_REPO_LOCAL
    end

    original_no_extra = @no_extra
    @no_extra = true
    begin
      product_definition_files(flavor).each do |def_file|
        puts "Processing def file #{def_file}"
        product_code = product_code(def_file)
        config = product_config(product_code, flavor)
        if postscripts = config['postscripts']
          if postscripts.find { |entry| entry.is_a?(Hash) && entry['maven-deploy'] }
            @product_code = product_code
            @flavor = flavor.downcase
            depends :init, :compile
            call_actions :__assemble
          end
        end
      end
    ensure
      @no_extra = original_no_extra
    end
  end

  def dist_maven_ee
    fail("Can only run this target under an EE checkout") unless @build_environment.is_ee_branch?
    dist_maven
    @internal_config_source['exclude-default-modules'] = 'true' # DEV-4134, modules_compile.rb picks this up
    @flavor = 'ENTERPRISE'
    @internal_config_source['flavor'] = @flavor
    dist_maven('ENTERPRISE')
  end

  def dist_dev(product_code = 'DSO', flavor = nil)
    flavor ||= @build_environment.is_ee_branch? ? 'ENTERPRISE' : 'OPENSOURCE'
    if flavor == 'ENTERPRISE' then dist_maven_ee else dist_maven end
    build_external
    dist(product_code, flavor)
  end
  
  # assemble and package the kits  for the product code supplied
  def create_package(product_code='DSO', flavor='OPENSOURCE')
    check_if_type_supplied(product_code, flavor)

    depends :init, :compile
    call_actions :__assemble, :__package
  end

  # assemble, package, and publish all possible kits (based on the configuration
  # files found under buildconfig/distribution directory)
  def create_all_packages(flavor='OPENSOURCE')
    @flavor = flavor.downcase

    depends :init, :compile
    srcdir        = @static_resources.distribution_config_directory.canonicalize.to_s
    product_codes = Dir.entries(srcdir).delete_if { |entry| (/\-(#{flavor})\.def\.yml$/i !~ entry) || (/^x\-/i =~ entry) }
    product_codes.each do |product_code|
      @product_code = product_code.sub(/\-.*$/, '')
      @flavor       = flavor
      call_actions :__assemble, :__package
      __publish @distribution_results.archive_dir.ensure_directory
    end
  end

  # assemble, package, and publish the kit for the product code supplied
  def publish_package(product_code='DSO', flavor='OPENSOURCE')
    check_if_type_supplied(product_code, flavor)

    depends :init, :compile
    call_actions :__assemble, :__package, :__publish
  end

  # assemble, package, and publish the kit for the product code supplied
  def publish_packages(product_codes, flavor)
    depends :init, :compile
    product_codes.each do |product_code|
      @product_code = product_code
      @flavor = flavor.downcase
      call_actions :__assemble, :__package, :__publish
    end
  end

  # assemble, package, and publish all possible kits (based on the configuration
  # files found under buildconfig/distribution directory)
  def publish_all_packages(flavor='OPENSOURCE')
    @flavor = flavor.downcase

    depends :init, :compile
    srcdir        = @static_resources.distribution_config_directory.canonicalize.to_s
    product_codes = Dir.entries(srcdir).delete_if { |entry| (/\-(#{flavor})\.def\.yml$/i !~ entry) || (/^x\-/i =~ entry) }
    product_codes.each do |product_code|
      @product_code = product_code.sub(/\-.*$/, '')
      @flavor       = flavor
      call_actions :__assemble, :__package, :__publish
    end
  end

  # HACK HACK HACK HACK HACK HACK HACK HACK HACK HACK HACK HACK
  # assemble, package, and publish all possible kits (based on the configuration
  # files found under buildconfig/distribution directory) for the opensource version
  # of the product
  def publish_opensource_packages
    publish_all_packages('OPENSOURCE')
  end

  # HACK HACK HACK HACK HACK HACK HACK HACK HACK HACK HACK HACK
  # assemble, package, and publish all possible kits (based on the configuration
  # files found under buildconfig/distribution directory) for the enterprise version
  # of the product
  def publish_enterprise_packages
    publish_all_packages('ENTERPRISE')
  end

  # build the JAR files for the product code supplied without assembling a full kit
  def dist_jars(product_code='DSO', component_name='common', flavor='OPENSOURCE')
    check_if_type_supplied(product_code, flavor)

    depends :init, :compile, :load_config
    component = get_spec(:bundled_components, []).find { |component| /^#{component_name}$/i =~ component[:name] }
    libdir    = FilePath.new(@distribution_results.build_dir, 'lib.tmp').ensure_directory
    destdir   = FilePath.new(@distribution_results.build_dir, 'lib').ensure_directory
    add_binaries(component, libdir, destdir)
    libdir.delete

    add_module_packages(component, destdir)
    ant.move(:todir => FilePath.new(File.dirname(destdir.to_s), 'tc-jars').to_s) do
      ant.fileset(:dir => destdir.to_s, :includes => '**/*')
    end
    destdir.delete
  end

  private
  def call_actions(*actions)
    load_config
    @distribution_results.clean(ant)
    actions.each { |action| method(action.to_sym).call }
  end

  require 'extensions/distribution-utils'
  include DistributionUtils

  require 'extensions/bundled-components'
  include BundledComponents

  require 'extensions/bundled-vendors'
  include BundledVendors

  require 'extensions/bundled-demos'
  include BundledDemos

  require 'extensions/bundled-modules'
  include BundledModules

  require 'extensions/packaging'
  include Packaging

  require 'extensions/postscripts'
  include Postscripts

  def __publish(archive_dir=nil)

    unless config_source["release-dir"].nil?
      release_dir = FilePath.new(config_source["release-dir"]).ensure_directory
    end

    destdir = release_dir || archive_dir              ||
      FilePath.new(config_source['build-archive-dir'] ||
        ".", @build_environment.current_branch, "rev#{@build_environment.os_revision}", @flavor).ensure_directory

    incomplete_tag = "__incomplete__"
    Dir.glob("#{@distribution_results.build_dir.to_s}/*").each do | entry |
      next if File.directory?(entry)
      filename            = File.basename(entry)
      incomplete_filename = destdir.to_s + "/" + filename + incomplete_tag
      dest_filename       = destdir.to_s + "/" + filename
      ant.copy(:file => entry, :tofile => incomplete_filename)
      FileUtils.rm(dest_filename) if File.exist?(dest_filename)
      ant.move(:file => incomplete_filename, :tofile => dest_filename)
    end
  end

  def __assemble
    exec_section :bundled_components
    exec_section :bundled_modules
    if @no_extra || @no_demo
      loud_message("--no-demo option found. No demos will be assembled")
    else
      exec_section :bundled_vendors
      exec_section :bundled_demos
    end
    puts "EXEC POSTSCRIPTS"
    exec_section :postscripts

    if (timlist = @config_source['timlist']) && (forgedir = @config_source['forgedir'])
      timlist = File.expand_path(timlist)
      forgedir = File.expand_path(forgedir)
      destdir = "#{product_directory.to_s}/platform/modules".gsub(/\\/, "/")

      cmd = "buildscripts/timbuild.sh #{timlist} #{forgedir} #{destdir}"

      puts "EXEC #{cmd}"
      result = system("bash #{cmd}")

      fail("timbuild.sh script failed") unless result
    end
  end

  def __package
    exec_section :packaging
    product_directory.delete
  end
end
