#
# All content copyright (c) 2003-2008 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#

require 'tmpdir'
require 'fileutils'
require 'cgi'

class BaseCodeTerracottaBuilder <  TerracottaBuilder
  protected

  # - extract hibernate referecne config from standalone agent jar
  def postscript(ant, build_environment, product_directory, *args)
    return if @no_extra
    return if @no_external_resources

    args.each do |resource_def|
      assemble_external_resource(resource_def)
    end
  end

  def assemble_external_resource(resource_def)
    resource_config = YAML.load_file(File.join(@basedir.to_s, resource_def))
    default_repos = resource_config['default_repos']
    puts "default repos: #{default_repos.join(',')}"
    artifacts = resource_config['artifacts']
    artifacts.each do |artifact|
      next if @no_demo && artifact['is_demo'] == true
      download_external(default_repos, product_directory.to_s, artifact)
    end
  end
end
