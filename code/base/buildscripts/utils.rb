#
# All content copyright (c) 2003-2008 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#
require 'rexml/document'
require 'rexml/xpath'

unless defined?(JavaSystem)
  include_class('java.lang.System') { |p, name| "Java" + name }
end

unless defined?(JavaFile)
  include_class('java.io.File') { |p, name| "Java" + name }
end

# A basic assertion facility for Ruby. Takes a block, and fails if
# the block returns false.
def assert(message="Assertion Failed")
  raise RuntimeError, message unless yield
end

# Prints a "loud", very visible message to the console.  This is used for
# warning messages that, even while the scroll by quickly on the console,
# should call out for the user's attention.
def loud_message(message)
  banner_char = "*"
  puts(banner_char * 80)
  puts(banner_char)
  puts("#{banner_char} #{message}")
  puts(banner_char)
  puts(banner_char * 80)
end

def time
  start = Time.now
  yield
  Time.now - start
end

def get_version(file, xpath)
  version = nil
  File.open(file) do |f|
    doc = REXML::Document.new(f)
    version = REXML::XPath.first(doc, xpath).text 
  end
  version
end

def compare_maven_version(maven_version, pom, xpath)
  version = get_version(pom, xpath)
  printf("%-50s: %s\n", pom, version)
  if version != maven_version
    raise "version #{version} in #{pom} " +
      "doesn't match maven.version #{maven_version}"
  end
end

def delete_deep_folder(folder)
  target = File.expand_path(folder)

  if File.file?(target)
    File.delete(target)
    return
  end

  queue = []
  queue << target

  while (! queue.empty?)  
    dir = queue.delete_at(0)
  
    Dir.chdir(dir) do 
      Dir.glob("*") do | f |
        num = 0
        next if (File.file?(f))
      
        begin
          File.rename(f, num.to_s)				
        rescue
          num += 1				
          retry                
        end
      
        newDir = File.expand_path(num.to_s)
        queue << newDir
        num += 1  
      end
    end

  end

  FileUtils.rm_rf target
end

def create_abnormal_junit_report(filename, classname)
  File.open(filename, "w") do |file|
    file.puts "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    file.puts "<testsuite errors=\"0\" failures=\"1\" name=\"#{classname.xml_escape}\" tests=\"1\" time=\"0.000\">"
    file.puts "<testcase classname=\"#{classname.xml_escape}\" name=\"test\" time=\"0.0\">"
    file.puts "  <failure type=\"junit.framework.AssertionFailedError\" message=\"Failed abnormally\">"
    file.puts "      Failed abnormally"
    file.puts "   </failure>"
    file.puts "</testcase>"
    file.puts "<system-out/><system-err/>"
    file.puts "</testsuite>"
  end
end
  
def load_yaml_erb(file, _binding)
  YAML.load(ERB.new(File.read(file), 0, "%<>").result(_binding))
end

class Hash
  def boolean(key, default = false)
    if has_key?(key)
      value = self[key]
      if value.is_a?(String)
        case value.downcase.strip
        when /^true$/: true
        when /^false$/: false
        when "": default
        else
          raise("Cannot convert value for key '#{key}' to boolean: #{value}")
        end
      else
        value ? true : false
      end
    else
      default
    end
  end
end

module CallWithVariableArguments
  # A method to call a procedure that may take variable arguments, but
  # which issues much nicer error messages when something fails than
  # the default messages issued by JRuby.
  def call_proc_variable(proc, args)
    if proc.arity == 0
      proc.call
    elsif proc.arity > 0
      raise RuntimeError, "Insufficient arguments: proc takes %d, but we only have %d." % [ proc.arity, args.length ] if proc.arity > args.length
      proc.call(*(args[0..(proc.arity - 1)]))
    else
      required = proc.arity.abs - 1
      raise RuntimeError, "Insufficient arguments: proc takes %d, but we only have %d." % [ required, args.length ] if required > args.length
      proc.call(*args)
    end
  end
end


def download_external(default_repos, dest_dir, artifact)
  success = false
  default_repos.each do |repo|
    url = artifact['maven_artifact'] == true ? create_maven_url(repo, artifact) : artifact['url']
    puts "Fetching #{url}"
    if is_live?(url)
      dest = File.join(dest_dir, artifact['destination'])
      FileUtils.mkdir_p(dest) unless File.directory?(dest)
      
      if artifact['explode'] == true
        tmp_dir = File.join("build", "tmp")
        FileUtils.mkdir_p(tmp_dir)
        dest_file = artifact['maven_artifact'] == true ? File.join(tmp_dir, File.basename(url)) : File.join(tmp_dir, artifact['name'])
        ant.get(:src => url, :dest => dest_file, :verbose => true)
        exploded_dir = File.join(tmp_dir, "exploded")
        FileUtils.mkdir_p(exploded_dir)
        
        if dest_file =~ /tar.gz$/
          ant.untar(:src => dest_file, :dest => exploded_dir, :compression => "gzip")
        elsif dest_file =~ /(jar|zip)$/
          ant.unzip(:src => dest_file, :dest => exploded_dir)
        else
          raise("Don't know how to unpack file #{dest_file}")
        end

        # recover execution bits
        ant.chmod(:dir => exploded_dir, :perm => "ugo+x", :includes => "**/*.sh **/*.bat **/*.exe **/bin/** **/lib/**")
        
        # assume the zip file contains a root folder
        root_dir = nil
        Dir.new(exploded_dir).each do |e|
          next if e =~ /^\./
          root_dir = File.expand_path(File.join(exploded_dir, e))
        end
        if artifact['remove_root_folder'] == true
          ant.move(:todir => dest) do
            ant.fileset(:dir => root_dir)
          end
        else
          ant.move(:file => root_dir, :todir => dest)
        end
        FileUtils.rm_rf(tmp_dir)
      else
        dest_file = artifact['maven_artifact'] == true ? File.join(dest, File.basename(url)) : File.join(dest, artifact['name'])
        ant.get(:src => url, :dest => dest_file, :verbose => true)
      end
      success = true
      break
    end
  end
  raise("Couldn't find artifact #{artifact} on any of the repos") unless success
end

def create_maven_url(repo, artifact)
  extension = artifact['type'] || 'jar'
  classifier = artifact['classifier'] ? "-#{artifact['classifier']}" : ''
  filename = "#{artifact['artifactId']}-#{artifact['version']}#{classifier}.#{extension}"

  "#{repo}/#{artifact['groupId'].gsub('.', '/')}/#{artifact['artifactId']}/#{artifact['version']}/#{filename}"
end

def is_live?(url_string)
  begin
    url = URI.parse(url_string)
    response = nil
    Net::HTTP.start(url.host, url.port) { |http|
      response = http.head(url.path.size > 0 ? url.path : "/")
    }
    forwarded_link = response['Location']
    if forwarded_link && forwarded_link != url_string
      return is_live?(forwarded_link)
    else
      return response.code == "200"
    end
  rescue
    return false
  end
end
