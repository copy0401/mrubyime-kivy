# Copyright 2026 copy0401
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Keyboard layout DSL (v1).
#
# A layout is a declarative description of the candidate bar and the key grid.
# `Keyboard.layout` only stores the block; `Keyboard.layout_serialized` evaluates
# it with the current context and returns the IR as a JSON string.
#
# IMPORTANT (device build): this file runs inside mruby 3.3 without the `regexp`
# gem, so it must not use Regexp, `gsub` with a pattern, or `eval`. It is compiled
# to bytecode at build time (keyboard_dsl_irep.c) and loaded by the JNI bridge.
# The same builder is used for user-imported layouts, in a throwaway mrb_state.

module Keyboard
  API_VERSION = 1

  class Error < StandardError; end

  # ------------------------------------------------------------------ JSON
  module Json
    def self.dump(value)
      case value
      when Hash
        "{" + value.map { |k, v| dump(k.to_s) + ":" + dump(v) }.join(",") + "}"
      when Array
        "[" + value.map { |v| dump(v) }.join(",") + "]"
      when String
        quote(value)
      when Symbol
        quote(value.to_s)
      when nil
        "null"
      when true
        "true"
      when false
        "false"
      when Integer, Float
        value.to_s
      else
        quote(value.to_s)
      end
    end

    def self.quote(str)
      out = "\""
      str.each_char do |c|
        if c == "\\"
          out << "\\\\"
        elsif c == "\""
          out << "\\\""
        elsif c == "\n"
          out << "\\n"
        elsif c == "\r"
          out << "\\r"
        elsif c == "\t"
          out << "\\t"
        elsif c.ord < 0x20
          out << sprintf("\\u%04x", c.ord)
        else
          out << c
        end
      end
      out << "\""
      out
    end
  end

  # --------------------------------------------------------------- context
  # Flat "key=value" lines produced by Kotlin (no JSON parser in mruby stdlib).
  def self.parse_context(text)
    ctx = {}
    return ctx if text.nil?
    text.split("\n").each do |line|
      line = line.strip
      next if line.empty?
      eq = line.index("=")
      next if eq.nil?
      key = line[0, eq]
      value = line[eq + 1, line.length - eq - 1]
      ctx[key.to_sym] = value
    end
    ctx
  end

  # ------------------------------------------------------------ registration
  def self.layout(opts = {}, &blk)
    version = (opts[:version] || API_VERSION).to_i
    raise Error, "unsupported layout version #{version} (max #{API_VERSION})" if version > API_VERSION
    raise Error, "layout block is missing" unless blk
    @version = version
    @block = blk
    version
  end

  def self.layout_serialized(context_text = "")
    raise Error, "no layout has been defined" unless @block
    ctx = parse_context(context_text)
    builder = Builder.new(@version || API_VERSION, ctx)
    builder.instance_exec(ctx, &@block)
    builder.to_json
  end

  # --------------------------------------------------------------- helpers
  def self.num(value)
    value.is_a?(Integer) ? value : value.to_f
  end

  def self.action_hash(action, value, fallback_text)
    case action
    when nil
      { "a" => "noop" }
    when Hash
      h = {}
      action.each { |k, v| h[k.to_s] = v.to_s }
      h
    else
      h = { "a" => action.to_s }
      actual = value
      actual = fallback_text if actual.nil? && action == :code
      h["v"] = actual.to_s unless actual.nil?
      h
    end
  end

  def self.font_hash(opts)
    return nil if opts.nil?
    h = {}
    opts.each { |k, v| h[k.to_s] = v.is_a?(Symbol) ? v.to_s : (v.is_a?(String) ? v : num(v)) }
    h
  end

  def self.style_hash(opts, flat)
    h = {}
    if opts
      opts.each { |k, v| h[k.to_s] = v.is_a?(Symbol) ? v.to_s : v }
    end
    h["flat"] = true if flat
    h.empty? ? nil : h
  end

  # `gestures: { swipe_up: "W" }` is a code action; symbols are actions.
  # A hash may use the short IR form (`{ a: :combo, v: "shift+enter" }`) or the
  # friendly form (`{ action: :combo, value: "shift+enter" }`).
  def self.gestures_hash(opts)
    return nil if opts.nil?
    h = {}
    opts.each do |k, v|
      h[k.to_s] =
        if v.is_a?(String)
          { "a" => "code", "v" => v }
        elsif v.is_a?(Hash) && (v.key?(:action) || v.key?("action"))
          action_hash(v[:action] || v["action"], v[:value] || v["value"], nil)
        else
          action_hash(v, nil, nil)
        end
    end
    h
  end

  # 邊框（bar 與候選 chip 共用）：width 單位 dp，color 是主題 token，
  # style 只接受 :solid / :dashed / :dotted，side 可選 :all（預設）或單邊。
  def self.border_hash(opts)
    return nil if opts.nil?
    width = opts[:width].nil? ? 0 : opts[:width]
    return nil if num(width) <= 0
    h = {
      "width" => num(width),
      "color" => (opts[:color] || :seam).to_s,
      "style" => (opts[:style] || :solid).to_s,
      "side" => (opts[:side] || :all).to_s
    }
    h
  end

  def self.repeat_hash(opts)
    return nil if opts.nil?
    { "delay" => num(opts[:delay] || 400), "interval" => num(opts[:interval] || 80) }
  end

  # Build one key definition (shared by rows, groups and the bar).
  def self.key_hash(label, opts = {})
    symbolic = label.is_a?(Symbol)
    text = symbolic ? (opts[:label] || "").to_s : label.to_s
    id = opts[:id] ? opts[:id].to_s : (symbolic ? label.to_s : nil)
    span = opts[:span].nil? ? 1 : opts[:span]
    action = opts[:action]
    action = :noop if action.nil? && symbolic
    action = :code if action.nil? && !symbolic

    h = {
      "id" => id,
      "span" => num(span),
      "label" => text,
      "action" => action_hash(action, opts[:value], text)
    }
    h["role"] = opts[:role].to_s if opts[:role]
    h["weight"] = num(opts[:weight]) if opts[:weight]
    h["subLabel"] = opts[:sub_label].to_s if opts[:sub_label]
    h["align"] = opts[:align].to_s if opts[:align]
    # Mode-dependent text: label_zh is used in Chinese mode, label_en in English.
    h["labelZh"] = opts[:label_zh].to_s if opts[:label_zh]
    h["labelEn"] = opts[:label_en].to_s if opts[:label_en]
    h["subLabelZh"] = opts[:sub_label_zh].to_s if opts[:sub_label_zh]
    h["subLabelEn"] = opts[:sub_label_en].to_s if opts[:sub_label_en]
    h["sound"] = opts[:sound].to_s if opts[:sound]
    h["soundVolume"] = num(opts[:sound_volume]) if opts[:sound_volume]
    h["icon"] = opts[:icon].to_s if opts[:icon]
    h["desc"] = opts[:desc].to_s if opts[:desc]
    h["transform"] = opts[:transform].to_s if opts[:transform]
    # How a long label fits its cell: :wrap breaks every `label_wrap` glyphs,
    # :shrink keeps one line and reduces the font, :none leaves it as written.
    h["labelFit"] = opts[:label_fit].to_s if opts[:label_fit]
    h["labelWrap"] = num(opts[:label_wrap]) if opts[:label_wrap]
    h["font"] = font_hash(opts[:font]) if opts[:font]
    style = style_hash(opts[:style], opts[:flat])
    if style.nil?
      style = {} if opts[:ink] || opts[:bg]
    end
    if style
      style["ink"] = opts[:ink].to_s if opts[:ink]
      style["bg"] = opts[:bg].to_s if opts[:bg]
      h["style"] = style
    end
    gestures = gestures_hash(opts[:gestures])
    h["gestures"] = gestures if gestures
    h["longPress"] = action_hash(opts[:long_press], nil, nil) if opts[:long_press]
    h["repeat"] = repeat_hash(opts[:repeat]) if opts[:repeat]
    h["haptic"] = opts[:haptic].to_s if opts[:haptic]
    h
  end

  # --------------------------------------------------------------- builders
  class ThemeBuilder
    attr_reader :colors

    def initialize
      @colors = {}
      @states = {}
    end

    def color(token, value)
      @colors[token.to_s] = value.to_s
    end

    def state(name, &blk)
      b = ThemeBuilder.new
      b.instance_exec(&blk)
      @states[name.to_s] = b.colors
    end

    def to_h
      { "colors" => @colors, "states" => @states }
    end
  end

  class Builder
    def initialize(version, ctx)
      @version = version
      @ctx = ctx
      @grid = { "columns" => 10, "gapH" => 1, "gapV" => 1 }
      @height = { "bar" => 48, "body" => 240 }
      @padding = { "left" => 0, "right" => 0, "bottom" => 0 }
      @min_touch = 48
      @themes = {}
      @pages = []
      @sound = nil
    end

    def grid(opts = {})
      @grid = {
        "columns" => Keyboard.num(opts[:columns] || 10),
        "gapH" => Keyboard.num(opts[:gap_h].nil? ? 1 : opts[:gap_h]),
        "gapV" => Keyboard.num(opts[:gap_v].nil? ? 1 : opts[:gap_v]),
        "outer" => Keyboard.num(opts[:outer].nil? ? 0.5 : opts[:outer])
      }
    end

    def height(opts = {})
      @height = {
        "bar" => Keyboard.num(opts[:bar] || 48),
        "body" => Keyboard.num(opts[:body] || 240)
      }
    end

    def padding(opts = {})
      @padding = {
        "left" => Keyboard.num(opts[:left] || 0),
        "right" => Keyboard.num(opts[:right] || 0),
        "bottom" => Keyboard.num(opts[:bottom] || 0)
      }
    end

    def min_touch(dp)
      @min_touch = Keyboard.num(dp)
    end

    # Layout-wide key sound. `name` is :click/:system/:none or a file name
    # (assets/sounds/<name>, filesDir/sounds/<name>). Per-key `sound:` overrides.
    #   sound :click
    #   sound :click, volume: 0.6
    def sound(name, opts = {})
      @sound = {
        "default" => name.to_s,
        "volume" => Keyboard.num(opts[:volume].nil? ? 1.0 : opts[:volume])
      }
    end

    def theme(name, &blk)
      b = ThemeBuilder.new
      b.instance_exec(&blk)
      @themes[name.to_s] = b.to_h
    end

    def page(name, opts = {}, &blk)
      cond = opts[:when]
      active = cond.nil? ? true : !!cond.call(@ctx)
      return unless active
      p = PageBuilder.new(name)
      p.instance_exec(@ctx, &blk)
      @pages << p.to_h
    end

    def to_h
      h = {
        "v" => @version,
        "grid" => @grid,
        "height" => @height,
        "padding" => @padding,
        "minTouch" => @min_touch,
        "themes" => @themes,
        "defaultPage" => (@pages.empty? ? "" : @pages[0]["name"]),
        "pages" => @pages
      }
      h["sound"] = @sound unless @sound.nil?
      h
    end

    def to_json
      Json.dump(to_h)
    end
  end

  class PageBuilder
    def initialize(name)
      @name = name.to_s
      @bar = nil
      @rows = []
    end

    def bar(opts = {}, &blk)
      b = BarBuilder.new(opts)
      b.instance_exec(&blk)
      @bar = b.to_h
    end

    def rows(&blk)
      b = RowsBuilder.new
      b.instance_exec(&blk)
      @rows = b.to_a
    end

    def to_h
      h = { "name" => @name, "rows" => @rows }
      h["bar"] = @bar unless @bar.nil?
      h
    end
  end

  class BarBuilder
    def initialize(opts)
      @height = opts[:height]
      @bg = (opts[:bg] || :surface)
      @gap = opts[:gap].nil? ? 1 : opts[:gap]
      @align = (opts[:align] || :start).to_s
      # 整個 bar（候選列）的邊框，內縮繪製、不佔版面寬度：
      #   border width: 2, color: :seam, style: :solid   # :solid / :dashed / :dotted
      #   border width: 0                                # 不畫（預設）
      @border = Keyboard.border_hash(opts[:border])
      @slots = []
    end

    # `span:` = fixed share of the grid, `weight:` = share of what the
    # weightless slots leave over (same rule as keys in a row). Give every
    # slot a weight and they split the whole bar proportionally, e.g.
    # `buffer weight: 2` + `candidates weight: 9` + `hide weight: 1`.
    def buffer(opts = {})
      span = opts[:span].nil? ? 1.3 : opts[:span]
      h = {
        "type" => "buffer",
        "role" => "buffer",
        "span" => Keyboard.num(span),
        "format" => (opts[:format] || :uppercase).to_s,
        "maxChars" => Keyboard.num(opts[:max_chars] || 6)
      }
      h["weight"] = Keyboard.num(opts[:weight]) if opts[:weight]
      h["id"] = opts[:id].to_s if opts[:id]
      h["font"] = Keyboard.font_hash(opts[:font]) if opts[:font]
      h["style"] = Keyboard.style_hash(opts[:style], true)
      @slots << h
    end

    # `font:` sets the chip text (size in sp, weight :bold/:normal, family = a
    # font file in assets/fonts/, filesDir/fonts/ or the Android/data mirror), e.g.
    #   candidates weight: 9, font: { size: 24, weight: :bold, family: "NotoSansCJK" }
    def candidates(opts = {})
      span = opts[:span].nil? ? 5.7 : opts[:span]
      chip = chip_hash(opts)
      # Override chip style for candidates: use chip/chipInk instead of bar/ink
      chip["style"]["bg"] = "chip"
      chip["style"]["ink"] = "chipInk"
      h = {
        "type" => "candidates",
        "role" => "candidate_row",
        "span" => Keyboard.num(span),
        "chip" => chip,
        "scroll" => {
          "mode" => "page",
          "loop" => true,
          "followFinger" => true,
          "snapRatio" => 0.08
        },
        "autoscroll" => opts[:autoscroll].nil? ? true : !!opts[:autoscroll]
      }
      h["weight"] = Keyboard.num(opts[:weight]) if opts[:weight]
      h["id"] = opts[:id].to_s if opts[:id]
      @slots << h
    end

    def slot(id, opts = {}, &blk)
      g = GroupBuilder.new(id, opts)
      g.instance_exec(&blk)
      @slots << g.to_h
    end

    # 狀態燈：一排只顯示狀態的小燈（CapsLock / Shift / Ctrl / Alt / Super…）。
    #   status span: 1.5 do
    #     lamp :caps,  label: "A"
    #     lamp :shift, label: "⇧"
    #   end
    def status(opts = {}, &blk)
      b = StatusBuilder.new(opts)
      b.instance_exec(&blk)
      @slots << b.to_h
    end

    def key(label, opts = {})
      @slots << { "type" => "key", "key" => Keyboard.key_hash(label, opts) }
    end

    def to_h
      h = {
        "bg" => @bg.to_s,
        "gap" => Keyboard.num(@gap),
        "align" => @align,
        "slots" => @slots
      }
      h["height"] = Keyboard.num(@height) unless @height.nil?
      h["border"] = @border unless @border.nil?
      h
    end

    private

    # 候選字 chip 的樣式：
    #   chip_style: { bg: :chip, ink: :chipInk, selected: { border: { bottom: 3, color: :chipMark } } }
    #   border: { width: 1, color: :chipMark, style: :dashed }        # 未選中的所有 chip
    #   border_selected: { width: 3, color: :chipMark, style: :dotted, side: :bottom }
    def chip_hash(opts)
      # Start with base style, then merge opts[:style] if provided
      style = {
        "bg" => "bar",
        "ink" => "ink",
        "flat" => true
        # "selected" border removed — no default border; use border_selected: in DSL if needed
      }
      # Merge style from opts[:style] if provided (for buffer/candidates custom styling)
      if opts[:style]
        opts[:style].each { |k, v| style[k.to_s] = v }
      end
      plain = Keyboard.border_hash(opts[:border])
      picked = Keyboard.border_hash(opts[:border_selected])
      style["border"] = plain unless plain.nil?
      if picked
        selected = { "border" => picked }
        style["selected"] = selected
      end
      if opts[:chip_style]
        opts[:chip_style].each { |k, v| style[k.to_s] = v }
      end
      {
        "font" => Keyboard.font_hash(opts[:font]) || { "size" => 24, "weight" => "bold" },
        "padH" => 3,
        "gap" => 0.5,
        "label" => "selkey",
        "format" => "%s%s",
        "minWidthRef" => "0微",
        "style" => style,
        "placeholder" => { "framedWhen" => "chinese" }
      }
    end
  end

  class GroupBuilder
    def initialize(id, opts)
      @id = id.to_s
      @span = opts[:span].nil? ? 1 : opts[:span]
      @gap = opts[:gap].nil? ? 0 : opts[:gap]
      @weight = opts[:weight]
      @keys = []
    end

    def key(label, opts = {})
      @keys << Keyboard.key_hash(label, opts)
    end

    def to_h
      h = {
        "type" => "group",
        "id" => @id,
        "span" => Keyboard.num(@span),
        "gap" => Keyboard.num(@gap),
        "keys" => @keys
      }
      h["weight"] = Keyboard.num(@weight) if @weight
      h
    end
  end

  # Status lamps: read-only indicators (no tap action) for CapsLock / Shift /
  # Ctrl / Alt / Super / 中英 mode / buffer non-empty. Each lamp is one slot.
  class StatusBuilder
    def initialize(opts = {})
      @id = (opts[:id] || :status).to_s
      @span = opts[:span].nil? ? 1 : opts[:span]
      @gap = opts[:gap].nil? ? 0 : opts[:gap]
      @weight = opts[:weight]
      @lamps = []
    end

    # state: :caps / :shift / :ctrl / :alt / :meta / :latch / :english / :chinese
    #        / :buffer / :candidates
    def lamp(state, opts = {})
      @lamps << {
        "state" => state.to_s,
        "label" => opts[:label].to_s,
        "span" => Keyboard.num(opts[:span].nil? ? 1 : opts[:span]),
        "onInk" => (opts[:on_ink] && opts[:on_ink].to_s),
        "offInk" => (opts[:off_ink] && opts[:off_ink].to_s)
      }
    end

    alias indicator lamp

    def to_h
      h = {
        "type" => "status",
        "id" => @id,
        "span" => Keyboard.num(@span),
        "gap" => Keyboard.num(@gap),
        "lamps" => @lamps
      }
      h["weight"] = Keyboard.num(@weight) if @weight
      h
    end
  end

  class RowsBuilder
    def initialize
      @rows = []
    end

    def row(opts = {}, &blk)
      b = RowBuilder.new(opts)
      b.instance_exec(&blk)
      @rows << b.to_h
    end

    def to_a
      @rows
    end
  end

  class RowBuilder
    def initialize(opts)
      @height = opts[:height].nil? ? 1 : opts[:height]
      @align = (opts[:align] || :start).to_s
      @gap_h = opts[:gap_h]
      @keys = []
    end

    def key(label, opts = {})
      @keys << Keyboard.key_hash(label, opts)
    end

    def spacer(opts = {})
      span = opts[:span].nil? ? 1 : opts[:span]
      @keys << { "id" => nil, "span" => Keyboard.num(span), "label" => "", "action" => { "a" => "noop" }, "style" => { "flat" => true, "invisible" => true } }
    end

    def to_h
      h = {
        "height" => Keyboard.num(@height),
        "align" => @align,
        "keys" => @keys
      }
      h["gapH"] = Keyboard.num(@gap_h) unless @gap_h.nil?
      h
    end
  end
end

# Top-level entry point used by the JNI bridge.
def keyboard_layout_serialized(context_text = "")
  Keyboard.layout_serialized(context_text)
end
