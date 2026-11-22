MRuby::CrossBuild.new('android-arm64-v8a') do |conf|
  toolchain :clang
  bin = "#{ENV['ANDROID_NDK_HOME']}/toolchains/llvm/prebuilt/linux-x86_64/bin"
  conf.cc.command      = "#{bin}/aarch64-linux-android24-clang"
  conf.linker.command  = "#{bin}/aarch64-linux-android24-clang"
  conf.archiver.command= "#{bin}/llvm-ar"
  conf.cc.flags       << "-fPIC"
  # size build
  conf.cc.flags       << "-Os" << "-DNDEBUG" << "-g0"
  conf.cc.flags       << "-ffunction-sections" << "-fdata-sections"
  conf.cc.flags       << "-fvisibility=hidden"
  conf.cc.flags       << "-fno-unwind-tables" << "-fno-asynchronous-unwind-tables"
  conf.cc.flags       << "-flto=thin"
  conf.linker.flags   << "-flto=thin"
  conf.cc.defines     << "MRB_UTF8_STRING"
  # Uniliu's word list makes Hashes with >131072 entries; Hash#values/#keys
  # build an RArray of every entry, so lift mruby's default array cap.
  conf.cc.defines     << "MRB_ARY_LENGTH_MAX=0"
  # Larger GC arena: loading a large .cin table in one call then overflows the
  # default 100-slot arena, and the forced recycle can collect an object still
  # in use (surfacing as  /  / ).
  conf.cc.defines     << "MRB_GC_ARENA_SIZE=4096"
  # Minimal gem set — everything mrubyime_engine.rb / keyboard_dsl.rb touch.
  # (mruby-object-ext provides instance_exec for the DSL; mruby-sprintf the
  # \uXXXX quoting. Set/Fiber/Enumerator/Lazy/Time/Struct/Pack are NOT built.)
  conf.gem core: 'mruby-enum-ext'      # sort_by, each_with_index
  conf.gem core: 'mruby-array-ext'
  conf.gem core: 'mruby-hash-ext'
  conf.gem core: 'mruby-string-ext'
  conf.gem core: 'mruby-symbol-ext'
  conf.gem core: 'mruby-range-ext'
  conf.gem core: 'mruby-numeric-ext'
  conf.gem core: 'mruby-kernel-ext'
  conf.gem core: 'mruby-proc-ext'
  conf.gem core: 'mruby-object-ext'    # instance_exec (DSL builders)
  conf.gem core: 'mruby-sprintf'
  # Needed on-device to compile user-imported keyboard layouts (Ruby DSL).
  # The built-in layout is shipped as bytecode and needs no compiler at runtime.
  conf.gem core: 'mruby-compiler'
end
