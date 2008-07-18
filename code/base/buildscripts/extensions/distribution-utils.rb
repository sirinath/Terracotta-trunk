#
# All content copyright (c) 2003-2008 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#

module DistributionUtils
  def exec_section(name)
    get_spec(name, []).each do |section|
      (section[:install_directories] || [(section[:install_directory] || '')]).each do |directory|
        send(name, section[:name], directory, section)
      end
    end
  end

  def product_config(product_code, flavor = 'OPENSOURCE')
    product_code = product_code.downcase
    flavor = flavor.downcase
    check_if_type_supplied(product_code, flavor)
    
    # until DEV-1253 is resolved, all distribution files should be in one place
    # so changes can be applied to them at one time. 
    # We decided to have all these files in OSS branch
    config_directory = @static_resources.distribution_config_directory('OPENSOURCE')
    
    filename = FilePath.new(config_directory,
      "#{product_code}-#{flavor}.def.yml").canonicalize.to_s
    if File.exist?(filename)
      YAML.load_file(filename)
    else
      fail "You need to create a kit definition file named `#{filename}' before you can build distribution for a `#{product_code}' kit."
    end
  end

  def product_definition_files(flavor)
    srcdir = @static_resources.distribution_config_directory(flavor).canonicalize.to_s
    Dir.entries(srcdir).delete_if { |entry|
      (/\-(#{flavor})\.def\.yml$/i !~ entry) || (/^x\-/i =~ entry)
    }
  end

  def product_code(product_definition_filename)
    if product_definition_filename =~ /^(\w+?)\-.+\.def\.yml$/
      $1
    else
      raise("Invalid product definition file: #{product_definition_filename}")
    end
  end

  def load_config
    product_code = (@product_code || @config_source["product.code"]).downcase
    flavor       = (@flavor || @config_source["flavor"]).downcase

    @config = product_config(product_code, flavor)
    @distribution_results = DistributionResults.new(self.dist_directory)
  end

  def check_if_type_supplied(product_code, flavor)
    fail 'You need to tell me the type of kit to build: DSO?'                         if product_code.nil?
    fail 'You need to tell me the flavor of the kit to build: OPENSOURCE|ENTERPRISE?' if flavor.nil?
    @product_code = product_code
    @flavor       = flavor.downcase
  end

  def patch_descriptor_file
    @config_source['patch_def_file'] ? FilePath.new(@config_source['patch_def_file']) : FilePath.new(@basedir, 'patch.def.yml')
  end

  def dist_directory
    FilePath.new(@build_results.build_dir, "dist")
  end

  def product_directory
    FilePath.new(@distribution_results.build_dir, get_config(:package_directory)).ensure_directory
  end

  def dorevpath(component)
    suffix = 'dorev' unless component[:install_directory].nil?
    FilePath.new(product_directory, (component[:install_directory] || ''), (suffix || ''))
  end

  def docspath(component)
    suffix = 'docs' unless component[:install_directory].nil?
    FilePath.new(product_directory, (component[:install_directory] || ''), (suffix || ''))
  end

  def libpath(component)
    FilePath.new(product_directory, (component[:install_directory] || ''), 'lib')
  end

  def native_libpath(component)
    FilePath.new(libpath(component), 'native')
  end

  def package_filename
    pattern = get_config(:kit_name_pattern).downcase
    pattern
  end

  def get_spec(symbol, default=nil)
    out     = []
    configs = @config[symbol.to_s]
    configs.each do |item|
      if item.instance_of?(Hash)
        spec = { :name => item.keys[0] }
        spec.merge!(item.values[0])
        spec = symbolise_keys(spec) if spec.instance_of?(Hash)
        out << spec if spec[:assert].nil? || eval(spec[:assert])
      else
        out << { :name => item }
      end
    end unless configs.nil?
    out
  end

  def get_config(symbol, default=nil)
    out = case symbol
    when :version           then @build_environment.version
    when :package_directory then @config[symbol.to_s] || "#{get_config(:root_directory)}"
    else
      @config[symbol.to_s] || default
    end
    out = interpolate(out) unless out.nil?
    out
  end

  def symbolise_keys(hash)
    hash.each_key { |k| hash[k.to_sym] = hash.delete(k) unless k.instance_of?(Symbol) }
    hash
  end

  def docspath(component, install_directory=nil)
    suffix = install_directory unless install_directory.nil?
    if suffix.nil?
      suffix = 'docs' unless component[:install_directory].nil?
    end
    FilePath.new(product_directory, (component[:install_directory] || ''), (suffix || ''))
  end

  def interpolate(s)
    s = s.gsub(/version/, @build_environment.version)
    s = s.gsub(/branch/, @build_environment.current_branch)
    s = s.gsub(/platform/, @build_environment.os_family.downcase)
    s = s.gsub(/revision/, @build_environment.current_revision.to_s)
    s = s.gsub(/edition/, @build_environment.edition)
    s = s.downcase
    s
  end
end
