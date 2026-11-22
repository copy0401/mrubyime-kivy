# Host-only build used to profile the engine with the SAME feature set as the
# device (UTF-8 strings, lifted array cap, big GC arena). Test aid only:
# build_mruby.sh does not use this file.
MRuby::Build.new('host-test') do |conf|
  toolchain :gcc
  conf.cc.defines << "MRB_UTF8_STRING"
  conf.cc.defines << "MRB_ARY_LENGTH_MAX=0"
  conf.cc.defines << "MRB_GC_ARENA_SIZE=4096"
  conf.gembox 'stdlib'
  conf.gembox 'stdlib-ext'
  conf.gem core: 'mruby-compiler'
  conf.gem core: 'mruby-bin-mruby'
  conf.gem core: 'mruby-bin-mrbc'
  conf.gem core: 'mruby-time'
  conf.gem core: 'mruby-io'
end
