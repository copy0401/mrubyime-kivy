# mrubyime input engine (class-only; user-editable at runtime).
#
# The bridge (mrubyime_jni.c) instantiates `class MrubyimeEngine` and calls
# exactly these instance methods - there is no other contract:
#
#   load_dictionary(data)   selkeys   legal_keys_serialized
#   input(key)              buffer    keynames_serialized
#   input_serialized(key)   reset     dictionary_name
#   current_candidates_serialized    table_max_code_len
#   backspace/backspace_serialized   entry_count
#   can_extend? would_extend?(key)   dead_prefix
#   set_max_code_len(len)   table_size
#
# Any .cin table is supported. Directives:
#   %ename / %cname   display name
#   %selkey           candidate selection keys (default 0123456789)
#   %keyname begin    physical key -> key-cap label (also defines legal keys)
#   %chardef begin    code -> candidate(s)
#   %phrase begin     concatenated-code -> phrase(s)
#
# Beyond the single-character table the engine also provides:
#   * PHRASES: a %phrase section maps the concatenated codes of a few
#     characters to a whole word, so typing the codes back-to-back returns
#     the phrase as one candidate. Two forms are accepted:
#       <code> <word> [...]   explicit code (hand-written)
#       <word> [...]          word-only: the code is derived from each
#                             character's shortest code
#   * WEIGHTS: either form may end with a frequency weight, a trailing field
#     of pure ASCII digits ("你好 121", "一一九 21"). It only counts when the
#     line has at least two fields, so a word that IS a number stays a word.
#     Weights feed @phrase_freq (word -> weight) and make rank_by_weight put
#     the most common reading first; a table without weights is unaffected.
#   * SINGLE-CHARACTER WORDS ARE IGNORED: a %phrase word of length 1 (and its
#     weight) is dropped before derivation, so a code's own characters keep
#     exactly the order %chardef gave them and a %phrase line can never
#     reorder or duplicate them.
#   * WORD LIST: the word-only form also feeds @words, a plain word set used
#     to rank auto-composition readings (a real word beats a nonsense one).
#     Large lists (uniliu ships 160,537 lines) are stored in @phrases/@words
#     only - never in @phrase_prefixes, which would add ~840k entries and
#     seconds to the load.
#   * COMPLETION: when the buffer is a strict prefix of a code (no exact
#     hit) the engine returns the finished candidates it could become,
#     capped at MAX_COMPLETIONS - i.e. auto-complete.
#   * COMPOSITION: a buffer that splits into single-character codes composes
#     a word. Each code contributes up to COMPOSE_CHARS_MAX characters (its
#     %chardef order, primary first), because one code can hold several
#     homographs (`wwn -> 違 遼 這 …`): using only the first one made 17% of
#     the table's words impossible to compose and typing an alternative code
#     for a character silently returned the wrong word (`wwnjn` -> 違是).
#     Readings are ranked word-set -> weight -> fewest codes, so a real word
#     beats a homograph-only reading.
#
# Device runtime notes:
#  * Do NOT write `@table[c] ||= []` - the runtime compiler mis-dispatches
#    the method name for `||=` on a Hash#[] receiver. Use a temp var.
#  * No File/IO/Thread/system/eval (mruby-io / mruby-eval gems are absent).
#  * No endless loops: mruby has no instruction limit; an infinite loop
#    freezes the IME and is quarantined on the next start.
#
# Replace the built-in logic by editing this file and recompiling with
# ./build_mruby.sh, or ship a user engine.rb (Settings -> Input Engine).
class MrubyimeEngine
  attr_reader :selkeys, :buffer, :keynames, :legal_keys, :max_code_len,
              :table_max_code_len, :name

  # Fallback when a table has neither %keyname nor codes.
  DEFAULT_LEGAL_KEYS = ("abcdefghijklmnopqrstuvwxyz" + ",.'[]").chars.freeze

  # Most candidates returned at once (~9 fit on screen; the rest scroll, and
  # composition + tail guesses need the extra room: the intended word is often
  # past the ninth reading).
  MAX_COMPLETIONS = 16

  # Characters per code tried while composing (see walk_segments). 3 recovers
  # 98.6% of the shipped table's words (1 char: 83%, 2: 95.7%, 4: 99.5%) at
  # ~1.5x branching, which the existing segmentation budget absorbs.
  COMPOSE_CHARS_MAX = 3

  # Guesses for a half-typed final code (see tail_readings). Collected apart
  # from the real readings and merged afterwards, so they never crowd them out.
  # TAIL_HEADS = finished prefixes kept per split point (the K=3 homographs
  # would otherwise multiply: `wwnj` needs 這, the 3rd character of `wwn`).
  # TAIL_LOOKAHEAD = how many extra keys may finish the tail.
  TAIL_MAX = 200
  TAIL_HEADS = 4
  # Per split point: codes scanned (canonical ones first) and words kept.
  TAIL_CODES = 240
  TAIL_MATCHES = 8
  TAIL_LOOKAHEAD = 2

  # Composition search limits. SEG_READINGS = how many finished segmentations
  # the walk keeps; the canonical-only pass is bounded much tighter because it
  # only branches once per code.
  SEG_READINGS = 120
  SEG_BUDGET = 40000
  SEG_BUDGET_CANON = 3000

  # Optional built-in phrases. Uncomment the ones you want (key = 
  # concatenated 形碼 codes, verified against assets/mrubyime.cin).
  BUILTIN_PHRASES = {
    # "ayyedn" => ["今天"],
    # "pnsgz" => ["你好"],
    # "ciwx" => ["中文"],
    # "jtinzj" => ["打字"],
    # "ixpmi" => ["我們"],
    # "pnspmi" => ["你們"],
    # "palpmi" => ["他們"],
    # "ednkm" => ["天氣"],
    # "dsekbu" => ["星期"],
    # "daamdo" => ["時間"],
    # "iqlulxu" => ["電腦"],
    # "hjg" => ["手機"],
    # "ypfpben" => ["老師"],
    # "uuexux" => ["朋友"],
    # "isaisa" => ["謝謝"],
    # "enymrl" => ["再見"],
    # "cioaqe" => ["中國"],
    # "uowisq" => ["台灣"],
    # "pdedez" => ["但是"],
    # "ixhn" => ["我很"],
  }.freeze

  def initialize
    @table = {}
    # Every strict prefix of a code (lets the IME tell a radical that can
    # still grow from a finished one).
    @prefixes = {}
    # prefix -> codes that finish it in <= TAIL_LOOKAHEAD keys (see load_dictionary)
    @tail_index = {}
    # concatenated-code -> phrase candidates
    @phrases = {}
    # word-only %phrase entries (word -> true); ranks composition readings
    @words = {}
    # word -> weight (the trailing integer of a `%phrase` line). 0 = unweighted.
    # Kept apart from @phrases so the arrays themselves stay plain Strings.
    # Only multi-character words get a weight (single-char lines are ignored).
    @weighted = false
    # strict prefixes of *explicit* phrase codes (never the huge word list)
    @phrase_prefixes = {}
    # reverse index: char -> shortest code (for word-only %phrase)
    @code_of = {}
    @seg_budget = 40000
    @buffer = ""
    @selkeys = "0123456789"
    @keynames = {}
    @legal_keys = DEFAULT_LEGAL_KEYS.dup
    @table_max_code_len = 5
    @max_code_len = 5
    @name = ""
    @ename = ""
  end

  # Called over JNI when the 最大碼清空組字 setting changes.
  def set_max_code_len(len)
    @max_code_len = len.to_i
  end

  def load_dictionary(data)
    @table.clear
    @keynames.clear
    @prefixes.clear
    @phrases.clear
    @words.clear
    @weighted = false
    @phrase_prefixes.clear
    @tail_index.clear
    @code_of.clear
    @buffer = ""
    @selkeys = "0123456789"
    @name = ""
    @ename = ""

    data = data.sub("\uFEFF", "")
    section = nil
    maxlen = 0
    chars = {}

    data.each_line do |line|
      # `strip` already removes the trailing "\n"/"\r"; skipping `chomp` saves
      # one string allocation per line (187k lines in the full uniliu table).
      line = line.strip
      next if line.empty?
      next if line.start_with?("#")

      if line.start_with?("%")
        if line.start_with?("%selkey")
          parts = line.split(" ")
          # Key caps matched lowercase; uppercase QWERTYUIOP is the lower row
          # of a 注音 keyboard and must still select candidates.
          @selkeys = parts[1].strip.downcase if parts[1] && !parts[1].empty?
        elsif line.start_with?("%cname")
          parts = line.split(" ", 2)
          @name = parts[1].strip if parts[1]
        elsif line.start_with?("%ename")
          parts = line.split(" ", 2)
          @ename = parts[1].strip if parts[1]
        elsif line.start_with?("%keyname begin")
          section = :keyname
        elsif line.start_with?("%chardef begin")
          section = :chardef
        elsif line.start_with?("%phrase begin")
          section = :phrase
        elsif line.start_with?("%keyname end") ||
              line.start_with?("%chardef end") ||
              line.start_with?("%phrase end")
          section = nil
        end
        next
      end

      case section
      when :keyname
        key, label = line.split(" ", 2)
        if key && label && !key.empty? && !label.empty?
          k = key.downcase.freeze
          @keynames[k] = label.strip
          chars[k] = true if k.length == 1
        end
      when :chardef
        code, char = line.split(" ", 2)
        next unless code && char
        c = code.downcase
        next if c.empty?
        c.freeze
        # NOTE: avoid `@table[c] ||= []` (see header). Use a temp var.
        list = @table[c]
        list = [] unless list
        char.freeze
        list << char
        @table[c] = list
        if char.index(" ")
          char.split(" ").each do |ch|
            cur = @code_of[ch]
            @code_of[ch] = c if !cur || c.length < cur.length
          end
        else
          cur = @code_of[char]
          @code_of[char] = c if !cur || c.length < cur.length
        end
        maxlen = c.length if c.length > maxlen
        # Index loop instead of `c.each_char { }` / `(1...c.length).each { }`:
        # block dispatch dominates the .cin parse on device.
        clen = c.length
        ci = 0
        while ci < clen
          chars[c[ci]] = true
          @prefixes[(pi = c[0, ci]).freeze] = true if ci > 0
          ci += 1
        end
      when :phrase
        # Two forms (see the header):
        #   <code> <word> [...]  explicit phrase code
        #   <word> [...]         word-only, code derived from each word
        # Either form may end with a frequency weight ("你好 121"); see
        # split_weight.  Only explicit codes get a prefix index - indexing the
        # 139k words would add ~840k entries.
        #
        # SINGLE-CHARACTER WORDS ARE IGNORED (and so is their weight): the
        # ordering of a code's own characters is whatever %chardef says, and a
        # %phrase line must never reorder or duplicate them. The shipped table
        # carries ~21k such lines, so this also saves 21k derive_code calls.
        # Fast path first: a word-only line starts with a multi-byte (CJK)
        # character, so `getbyte(0) < 128` skips the explicit-code probe (a
        # downcase + slice per line) for the 139k shipped lines.
        if line.getbyte(0) < 128
          sp = line.index(" ")
          if sp && @table.key?(line[0, sp].downcase)
            pc = line[0, sp].downcase
            wts, weight = split_weight(line[sp + 1, line.length - sp - 1])
            list = @phrases[pc]
            list = [] unless list
            wts.each do |w|
              next if w.empty? || w.length == 1
              if weight > 0
                old = @words[w]
                @words[w] = weight if !old || weight > old
                @weighted = true
              else
                @words[w] = 0 unless @words.key?(w)
              end
              list << w unless list.include?(w)
            end
            @phrases[pc] = list
            maxlen = pc.length if pc.length > maxlen
            (1...pc.length).each { |i| @phrase_prefixes[pc[0, i]] = true }
          else
            wts, weight = split_weight(line)
            phrase_add_words(wts, weight)
          end
        else
          # word-only line: `word [weight]`. Parsed by index - no split array,
          # no per-line strip copies. Multi-word lines fall back to split.
          weight = 0
          sp = line.rindex(" ")
          if sp
            tail = line[sp + 1, line.length - sp - 1]
            digits = true
            ti2 = 0
            tl = tail.length
            while ti2 < tl
              b = tail.getbyte(ti2)
              if b < 48 || b > 57
                digits = false
                break
              end
              ti2 += 1
            end
            if digits && !tail.empty?
              weight = tail.to_i
              word_part = line[0, sp]
              if word_part.index(" ")
                wts, weight2 = split_weight(line)
                phrase_add_words(wts, weight2)
              else
                ml = phrase_add_word(word_part, weight)
                maxlen = ml if ml > maxlen
              end
            else
              wts, weight2 = split_weight(line)
              phrase_add_words(wts, weight2)
            end
          else
            ml = phrase_add_word(line, 0)
            maxlen = ml if ml > maxlen
          end
        end
      end
    end

    # Seed the built-in words so phrases work without a %phrase section.
    BUILTIN_PHRASES.each do |pc, words|
      list = @phrases[pc]
      list = [] unless list
      words.each do |w|
        w = w.freeze
        list << w unless list.include?(w)
        @words[w] = 0 unless @words.key?(w)
      end
      @phrases[pc] = list
      maxlen = pc.length if pc.length > maxlen
      (1...pc.length).each { |i| @phrase_prefixes[pc[0, i]] = true }
    end

    @table_max_code_len = maxlen > 0 ? maxlen : 5
    @max_code_len = @table_max_code_len

    # Tail index: prefix -> codes that finish it with TAIL_LOOKAHEAD (2) more
    # keys at most. Separate from @prefixes (a membership set) because a plain
    # prefix can cover thousands of codes, and scanning that per keystroke cost
    # ~2 ms; a bucket holds at most a few dozen. ~14.4k slots for the shipped
    # table, built once here.
    @tail_index.clear
    @table.each do |c, _list|
      n1 = c.length - 1
      if n1 >= 1
        p = c[0, n1].freeze
        l = @tail_index[p]
        l = [] unless l
        l << c
        @tail_index[p] = l
      end
      n2 = c.length - 2
      if n2 >= 1
        p = c[0, n2].freeze
        l = @tail_index[p]
        l = [] unless l
        l << c
        @tail_index[p] = l
      end
    end

    keys = @keynames.keys
    extras = chars.keys.reject { |k| @keynames.key?(k) }
    keys = keys + extras.sort
    @legal_keys = keys.empty? ? DEFAULT_LEGAL_KEYS.dup : keys

    @name = @ename if @name.empty?
    @name = "自訂碼表" if @name.empty?
    # Full GC right after the load: the parse allocates ~1.5M temporaries and
    # their dead pages stay in the mruby heap (the incremental GC does not
    # shrink it eagerly). One full collection here drops them, which trims a
    # large part of the native peak the raw parse leaves behind.
    GC.start
  end

  # Store one word in the merged word/weight hash and its @phrases bucket.
  # The bucket is the word STRING itself for the common single-word case and
  # an Array only when a code really carries several words (742 of 138,878 in
  # the shipped table), so ~138k one-element arrays never exist.
  def phrase_add_word(w, weight)
    return if w.empty? || w.length == 1
    pc = derive_code(w)
    return if pc.empty?
    # Freezing lets Hash#[]= store the object itself instead of a dup
    # (mruby hash.c dups unfrozen String keys), so the word and its derived
    # code exist exactly once across @words / @phrases / @tail_index.
    w.freeze
    pc.freeze
    if weight > 0
      old = @words[w]
      @words[w] = weight if !old || weight > old
      @weighted = true
    else
      @words[w] = 0 unless @words.key?(w)
    end
    list = @phrases[pc]
    if list.nil?
      @phrases[pc] = w
    elsif list.is_a?(String)
      @phrases[pc] = [list, w] if list != w
    else
      list << w unless list.include?(w)
    end
    pc.length
  end

  def phrase_add_words(wts, weight)
    ml = 0
    wts.each do |w|
      n = phrase_add_word(w, weight)
      ml = n if n > ml
    end
    ml
  end

  # Split one `%phrase` line body into its words and its optional trailing
  # weight: the weight is a full-ASCII-digit last field and only counts when
  # the line has at least two fields, so a word that IS a number ("2024") is
  # never eaten. Returns [words, weight]; weight is 0 when absent.
  def split_weight(body)
    words = body.split(" ")
    n = words.length
    return [words, 0] if n < 2
    tail = words[n - 1]
    return [words, 0] if tail.empty?
    i = 0
    while i < tail.length
      ch = tail[i]
      return [words, 0] if ch < "0" || ch > "9"
      i += 1
    end
    [words[0, n - 1], tail.to_i]
  end

  # Concatenate each char's shortest code (word-only %phrase). Empty if any
  # char has no known code. Called once per word at load time, so it uses an
  # index loop: `each_char` block dispatch was ~20% of the whole load.
  def derive_code(word)
    out = ""
    n = word.length
    i = 0
    while i < n
      c = @code_of[word[i]]
      return "" unless c
      out << c
      i += 1
    end
    out
  end

  # All the ways to read the buffer as a run of single-char codes, ranked by
  # (1) whether the reading is a declared word in the table's word list, then
  # (2) its weight (heaviest reading first), then
  # (3) the number of codes used (fewer = the "most composed" reading first,
  # which is how shape codes are normally typed: one full code per character),
  # then (4) the cheapest characters, then (5) traversal order.
  # Readings whose last code is still a strict prefix are included too (the
  # word is completed generically), so the bar keeps showing the intended word
  # while the last code is half-typed.
  # Returns joined strings, dedup, capped. Empty when the buffer does not split.
  def compose_candidates(buf)
    # Compose up to whatever the IME allows the buffer to grow to: 140 chars
    # when "max code clear" is off (default), the table's max code when on.
    return [] if buf.empty? || buf.length > @max_code_len
    n = buf.length
    # Split points where the rest of the buffer is a prefix of a longer code
    # (`pnsg` -> 你 + `g`, where `gz` is 好). Computed up front: at most
    # @table_max_code_len positions, so the walk only does a Hash lookup.
    tails = nil
    k = 1
    while k < n
      rest = n - k
      if rest <= @table_max_code_len && @prefixes.key?(buf[k, rest])
        tails = [] unless tails
        tails[k] = true
      end
      k += 1
    end
    heads = []
    # Pass 1 - canonical characters only (one branch per code). Cheap, and it
    # guarantees the "one full code per character" reading survives: `segnfooo`
    # is s|egn|fooo (三峽區), but `seg` is also a code, and walking its
    # alternative-character subtree first used to exhaust the reading cap
    # before the `s` branch was ever reached (so the word never showed up).
    # Its own list, so pass 2 keeps a full budget of alternate readings
    # (`gnsq` -> 你們 needs 你, which is `gns`'s third character).
    canon = []
    @seg_budget = SEG_BUDGET_CANON
    walk_segments(buf, 0, [], canon, SEG_READINGS, tails, heads, 1)
    # Pass 2 - every code's alternative characters (COMPOSE_CHARS_MAX), so
    # `wwnjn` still finds 這是 (這 is not `wwn`'s first character).
    alt = []
    heads_alt = []
    @seg_budget = SEG_BUDGET
    walk_segments(buf, 0, [], alt, SEG_READINGS, tails, heads_alt, COMPOSE_CHARS_MAX)
    found = canon
    found.concat(alt)
    if tails
      # Two head sets: the canonical pass fills its 4 slots at a split point
      # with canonical prefixes, so the alternate prefixes (你 for `gns`) need
      # their own slots or `gnsq` loses 你們.
      tail_readings(buf, heads, found)
      tail_readings(buf, heads_alt, found)
    end
    return [] if found.empty?
    scored = []
    n = 0
    found.each do |segs|
      word = ""
      cost = 0
      segs.each do |pair|
        word << pair[1]
        cost += pair[2]
      end
      f = @words[word]
      rank = f ? 0 : 1
      f = 0 unless f
      scored << [rank, -f, segs.length, cost, n, word]
      n += 1
    end
    scored = scored.sort_by { |s| [s[0], s[1], s[2], s[3], s[4]] }
    out = []
    seen = {}
    scored.each do |s|
      w = s[5]
      next if seen[w]
      seen[w] = true
      out << w
      break if out.length >= MAX_COMPLETIONS
    end
    out
  end

  # DFS over every segmentation of `str`. `tails` (optional) is a Hash of
  # position -> true for the split points where the rest of the buffer is a
  # half-typed code; those readings are collected in `heads` (position -> up to
  # TAIL_HEADS finished prefixes) and completed cheaply by compose_candidates
  # *after* the walk. Doing it inside the walk would repeat the same code scan
  # once per visited node (40000 of them) and cost ~200 ms per key.
  def walk_segments(str, i, acc, found, limit, tails = nil, heads = nil, per_code = COMPOSE_CHARS_MAX)
    return if found.length >= limit
    @seg_budget -= 1
    return if @seg_budget <= 0
    if i >= str.length
      found << acc.dup
      return
    end
    if tails && tails[i]
      list = heads[i]
      list = [] unless list
      list << acc.dup if list.length < TAIL_HEADS
      heads[i] = list
    end
    cap = str.length - i
    cap = @table_max_code_len if cap > @table_max_code_len
    cap.downto(1) do |n|
      code = str[i, n]
      if @table.key?(code)
        # Same code, every character: a code like `wwn` holds 違/遼/這/…, so
        # stopping at the first one loses most of the table's words.
        list = @table[code]
        total = list.length
        total = per_code if total > per_code
        k = 0
        while k < total
          acc << [code, list[k], k]
          walk_segments(str, i + n, acc, found, limit, tails, heads, per_code)
          acc.pop
          k += 1
        end
      end
    end
  end

  # Finish a half-typed last code: for every split point the walk collected,
  # append the codes that complete the tail when the result is a word the table
  # knows (`pnsg` -> 你好 rather than the literal 你機, which comes from
  # 機 = `g`). Only completions within TAIL_LOOKAHEAD extra keys are tried: a
  # code finished with one more key is what the user is doing right now, and it
  # keeps the scan to a few dozen codes instead of thousands.
  def tail_readings(buf, heads, found)
    n = buf.length
    pairs_of = {}
    added = 0
    i = 0
    while i < heads.length
      segs_list = heads[i]
      if segs_list
        rest = n - i
        suffix = buf[i, rest]
        pairs = pairs_of[suffix]
        if pairs.nil?
          canon = []
          other = []
          codes = @tail_index[suffix]
          if codes
            last = rest + TAIL_LOOKAHEAD
            last = @table_max_code_len if last > @table_max_code_len
            want = rest + 1
            while want <= last
              ci = 0
              while ci < codes.length && (canon.length + other.length) < TAIL_CODES
                c = codes[ci]
                if c.length == want
                  chars = @table[c]
                  total = chars.length
                  total = COMPOSE_CHARS_MAX if total > COMPOSE_CHARS_MAX
                  k = 0
                  while k < total
                    ch = chars[k]
                    pr = [c, ch, k]
                    # The code the table derives for that character (gz for 好)
                    # comes first: it is the one the user most likely typed.
                    if @code_of[ch] == c
                      canon << pr
                    else
                      other << pr
                    end
                    k += 1
                  end
                end
                ci += 1
              end
              want += 1
            end
          end
          pairs = canon + other
          pairs_of[suffix] = pairs
        end
        if !pairs.empty?
          segs_list.each do |segs|
            word = ""
            segs.each { |pair| word << pair[1] }
            hits = 0
            pairs.each do |pr|
              next unless @words.key?(word + pr[1])
              reading = segs.dup
              reading << pr
              found << reading
              added += 1
              hits += 1
              return if added >= TAIL_MAX
              break if hits >= TAIL_MATCHES
            end
          end
        end
      end
      i += 1
    end
  end

  # Single best-reading (greedy-longest) used by can_extend?/dead_prefix.
  def compose_word(buf)
    list = compose_candidates(buf)
    return nil if list.empty?
    list[0]
  end

  # Weight of a candidate word (0 when the table never weighted it). Only
  # multi-character words are weighted: single-character %phrase lines are
  # ignored, so exact-char lookups keep %chardef's order.
  def weight_of(word)
    w = @words[word]
    w ? w : 0
  end

  # Stable sort from heaviest to lightest, deduped: one entry per distinct
  # word, kept at its first (heaviest) position. Equal weights keep the order
  # they came in with, so an unweighted table behaves exactly as before.
  def rank_by_weight(list)
    return list unless @weighted
    indexed = []
    seen = {}
    i = 0
    n = list.length
    while i < n
      w = list[i]
      unless seen.key?(w)
        seen[w] = true
        indexed << [weight_of(w), i, w]
      end
      i += 1
    end
    indexed = indexed.sort_by { |t| [-t[0], t[1]] }
    out = []
    indexed.each { |t| out << t[2] }
    out
  end

  # One entry per distinct candidate, first occurrence kept (a char and a
  # phrase can share the same text, and %chardef may list a char twice).
  def dedupe(list)
    out = []
    seen = {}
    list.each do |w|
      next if seen[w]
      seen[w] = true
      out << w
    end
    out
  end

  # The candidate set for the current buffer: exact chars, then phrases, then
  # completions (the finished candidates a live prefix could become).
  def candidates_for(buf)
    exact = @table[buf]
    ph = @phrases[buf]
    # If a single-char code and an explicit phrase collide, show both: the
    # characters first (the code's own meaning), then the phrase, so a user's
    # %phrase is never silently hidden by the char code. Weights reorder
    # inside each group only, never across them.
    if (exact && !exact.empty?) || (ph && !ph.empty?)
      out = []
      if exact && !exact.empty?
        out.concat(exact.length > 1 ? rank_by_weight(exact) : exact)
      end
      if ph && !ph.empty?
        if ph.is_a?(String)
          out << ph
        else
          out.concat(ph.length > 1 ? rank_by_weight(ph) : ph)
        end
      end
      return dedupe(out)
    end
    comp = compose_candidates(buf)
    return comp if !comp.empty?
    # Completion: buf is a strict prefix of a code / phrase code. Return the
    # finished candidates (dedup, capped) so the user can pick or keep typing.
    return [] if buf.empty? || buf.length >= @max_code_len
    out = []
    seen = {}
    if @prefixes.key?(buf)
      @table.each do |c, list|
        next unless c.length > buf.length && c.start_with?(buf)
        list.each do |ch|
          next if seen[ch]
          seen[ch] = true
          out << ch
          return out if out.length >= MAX_COMPLETIONS
        end
      end
    end
    if out.length < MAX_COMPLETIONS && @phrase_prefixes.key?(buf)
      @phrases.each do |c, list|
        next unless c.length > buf.length && c.start_with?(buf)
        if list.is_a?(String)
          next if seen[list]
          seen[list] = true
          out << list
          return out if out.length >= MAX_COMPLETIONS
        else
          list.each do |w|
            next if seen[w]
            seen[w] = true
            out << w
            return out if out.length >= MAX_COMPLETIONS
          end
        end
      end
    end
    out
  end

  def input(key)
    k = key.downcase
    @buffer << k
    return [] if @buffer.length > @max_code_len
    candidates_for(@buffer)
  end

  # Diagnostics: weight of one candidate (0 = unweighted) and how many
  # candidates carry a weight. Read-only; not part of the JNI surface.
  def weight_for(word)
    weight_of(word)
  end

  def phrase_weight_count
    n = 0
    @words.each_value { |w| n += 1 if w > 0 }
    n
  end

  def input_serialized(key)
    input(key).join("\n")
  end

  # Peek the candidates without modifying the buffer (used on mode switch).
  def current_candidates_serialized
    candidates_for(@buffer).join("\n")
  end

  # True when appending `key` stays on the way to a code / phrase.
  def would_extend?(key)
    probe = @buffer + key.downcase
    return false if probe.empty? || probe.length > @max_code_len
    @table.key?(probe) || @prefixes.key?(probe) ||
      @phrases.key?(probe) || @phrase_prefixes.key?(probe)
  end

  # True when some legal key can still extend the current buffer.
  def can_extend?
    return false if @buffer.empty?
    # A buffer that composes into a multi-char word can still grow: never
    # let auto-commit swallow it prematurely.
    w = compose_word(@buffer)
    return true if w && w.length > 1
    @legal_keys.each do |k|
      ext = @buffer + k
      return true if @table.key?(ext) || @prefixes.key?(ext) ||
        @phrases.key?(ext) || @phrase_prefixes.key?(ext)
    end
    false
  end

  # A short prefix with no exact candidate is cleared only when it is dead:
  # adding any legal key still yields nothing (not even a prefix/completion).
  def dead_prefix
    return false if @buffer.empty?
    # A composing buffer is alive even if no single code matches it.
    return false if compose_word(@buffer)
    exact = @table[@buffer]
    exact = @phrases[@buffer] if !exact || exact.empty?
    return false if exact && !exact.empty?
    return false if @prefixes.key?(@buffer) || @phrase_prefixes.key?(@buffer)
    @legal_keys.each do |k|
      ext = @buffer + k
      return false if @table.key?(ext) || @prefixes.key?(ext) ||
        @phrases.key?(ext) || @phrase_prefixes.key?(ext)
    end
    true
  end

  def backspace
    return [] if @buffer.empty?
    @buffer.chop!
    candidates_for(@buffer)
  end

  def backspace_serialized
    backspace.join("\n")
  end

  def reset
    @buffer = ""
  end

  def table_size
    @table.size
  end

  # Number of candidates across all codes and phrases.
  def entry_count
    n = 0
    @table.each_value { |v| n += v.length }
    @phrases.each_value { |v| n += (v.is_a?(Array) ? v.length : 1) }
    n
  end

  # Physical key -> key-cap label, one `key<TAB>label` per line.
  def keynames_serialized
    out = ""
    @keynames.each { |k, v| out << k << "\t" << v << "\n" }
    out
  end

  # All legal input keys, concatenated (each is one character).
  def legal_keys_serialized
    @legal_keys.join("")
  end

  def dictionary_name
    @name
  end
end
