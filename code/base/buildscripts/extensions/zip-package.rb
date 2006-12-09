#
# All content copyright (c) 2003-2006 Terracotta, Inc.,
# except as may otherwise be noted in a separate copyright notice.
# All rights reserved
#

class BaseCodeTerracottaBuilder <  TerracottaBuilder
  protected
  def make_package(srcdir, destdir, filename, install_name, internal_name)
    ant.zip(:destfile => FilePath.new("#{destdir}", "#{filename}.zip").to_s) do
      ant.zipfileset(:dir => srcdir.to_s, :prefix => install_name, :excludes => "**/*.sh **/*.bat **/*.exe **/bin/** **/libexec/**") 
      ant.zipfileset(:dir => srcdir.to_s, :prefix => install_name, :includes => "**/*.sh **/*.bat **/*.exe **/bin/** **/libexec/**", :filemode => 755) 
    end
  end
end