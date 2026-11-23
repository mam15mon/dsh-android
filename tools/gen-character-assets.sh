#!/usr/bin/env bash
# 从角色原图（docs/splash/dsh-character-original.webp，1024×1024，Gemini 生成）生成：
#   * app/src/main/res/drawable-nodpi/dsh_character_icon.png      开屏里跳动的角色图块（圆角外透明）
#   * app/src/main/res/drawable-nodpi/dsh_launcher_foreground.png 桌面图标前景（去掉底板描边后铺满）
# 需要 ImageMagick 7（magick）。换原图后量一下底板位置和圆角，改下面几个数再跑一次。
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=docs/splash/dsh-character-original.webp
OUT=app/src/main/res/drawable-nodpi

# 原图里圆角底板的位置（底板外是浅色背景和阴影）与圆角半径
TILE_X=137; TILE_Y=128; TILE_W=750; TILE_H=768; RADIUS=137
# 底板边缘的蓝：桌面图标底色与前景四角的填充色（res/values/dsh_brand_colors.xml 的 dsh_character_icon_bg）
EDGE='#023870'

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# 圆角遮罩（向内收 2px，避免把底板外的浅色背景带进来）
magick -size ${TILE_W}x${TILE_H} xc:black -fill white \
  -draw "roundrectangle 2,2 $((TILE_W-3)),$((TILE_H-3)) $((RADIUS-2)),$((RADIUS-2))" "$tmp/mask.png"
magick "$SRC" -crop ${TILE_W}x${TILE_H}+${TILE_X}+${TILE_Y} +repage "$tmp/tile.png"
magick "$tmp/tile.png" "$tmp/mask.png" -alpha off -compose CopyOpacity -composite "$tmp/tile_rgba.png"

# 开屏：整块底板，600px 宽
magick "$tmp/tile_rgba.png" -filter Lanczos -resize 600x -strip "PNG32:$OUT/dsh_character_icon.png"

# 桌面图标前景：去掉描边（INSET）取内部正方形，四角缺口填底色，放大到 432 画布的中间 304px，
# 可见区（中间 288px）之外再留一点余量，任何遮罩形状都看不到底板边框。
INSET=14
SIDE=$((TILE_W-2*INSET))
magick "$tmp/tile_rgba.png" -background "$EDGE" -flatten \
  -crop ${SIDE}x${SIDE}+${INSET}+$(((TILE_H-SIDE)/2)) +repage \
  -filter Lanczos -resize 304x304 -gravity center -background "$EDGE" -extent 432x432 \
  -strip "PNG24:$OUT/dsh_launcher_foreground.png"

magick identify "$OUT/dsh_character_icon.png" "$OUT/dsh_launcher_foreground.png"
