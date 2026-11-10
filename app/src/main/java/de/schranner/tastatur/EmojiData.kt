package de.schranner.tastatur

object EmojiData {

    class Category(val icon: String, val name: String, val emojis: List<String>)

    private fun list(s: String) = s.trim().split(Regex("\\s+"))

    val categories = listOf(
        Category("🙂", "Smileys", list("""
            😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 🫠 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑
            🤗 🤭 🫢 🫣 🤫 🤔 🫡 🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢
            🤮 🤧 🥵 🥶 🥴 😵 😵‍💫 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 🫤 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 🥹 😦 😧 😨
            😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖
            😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊
        """)),
        Category("👋", "Gesten & Menschen", list("""
            👋 🤚 🖐️ ✋ 🖖 🫱 🫲 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜
            👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 🫦 👶 🧒 👦 👧 🧑 👱 👨 👩
            🧔 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 👷 🤴 👸 👳 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶
            🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 👯 🧖 🧘 👫 👬 👭 💏 💑 👪
        """)),
        Category("❤️", "Herzen & Symbole", list("""
            ❤️ 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 🩷 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💯 💢 💥
            💫 💦 💨 🕳️ 💬 💭 💤 ✨ ⭐ 🌟 🔥 ⚡ 🎉 🎊 ✅ ❌ ❗ ❓ ‼️ ⁉️ ⚠️ 🚫 ⛔ 🆗 🆒 🆕 🆓 🔝 🔜
            ➕ ➖ ✖️ ➗ ♾️ 💲 💶 ©️ ®️ ™️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔶 🔷 ▶️ ⏸️ ⏹️ 🔁 🔀 🔔 🔕
            ☮️ ☯️ ✝️ ☪️ 🕉️ ✡️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆘 📍 🏁 🚩 🏳️ 🏴 🏳️‍🌈 🇩🇪 🇦🇹 🇨🇭 🇪🇺
        """)),
        Category("🐶", "Tiere & Natur", list("""
            🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄
            🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🕷️ 🐢 🐍 🦎 🦖 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓
            🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🕊️ 🐇 🦝 🦔
            🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌚 🌙 🌎 🪐
            ⭐ ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 🌪️ 🌫️ 🌈 ☔ 💧 🌊
        """)),
        Category("🍔", "Essen & Trinken", list("""
            🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🧄
            🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯
            🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭
            🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 ☕ 🫖 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾 🧊 🥄 🍴 🍽️
        """)),
        Category("⚽", "Aktivitäten & Reisen", list("""
            ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 ⛸️ 🎿 ⛷️ 🏂 🏋️ 🤸 🚴
            🏊 🧗 🏆 🥇 🥈 🥉 🏅 🎖️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳
            🎮 🎰 🧩 🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚨 🚔 🚍 🚘 🚖 🚡 🚠
            🚃 🚋 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🚀 🛸 🚁 🛶 ⛵ 🚤 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🗺️
            🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏢 🏬 🏥 🏦 🏨 ⛪
        """)),
        Category("💡", "Gegenstände", list("""
            ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⏳ ⌛ 🔋 🔌 💡 🔦 🕯️
            🧯 💸 💵 💶 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧱 ⛓️ 🧲 🔫 💣 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🔮
            📿 💈 ⚗️ 🔭 🔬 🩹 🩺 💊 💉 🩸 🧬 🦠 🧪 🌡️ 🧹 🧺 🧻 🚽 🚿 🛁 🧼 🪥 🧽 🛎️ 🔑 🗝️ 🚪 🪑
            🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🎊 🎉 ✉️ 📩 📨 📧 💌 📦 🏷️ 📫 📮 📜 📃 📄 📑 📊 📈
            📉 📆 📅 🗓️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🔗 📎 🖇️ 📐 📏
            📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓 👓 🕶️ 🥽 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👙
            👚 👛 👜 👝 🎒 👞 👟 🥾 👠 👡 👢 👑 👒 🎩 🎓 🧢 ⛑️ 💄 💍 💼
        """)),
    )

    /** Emoji-Vorschläge beim Tippen (Wort -> Emoji). */
    val keywords: Map<String, String> = mapOf(
        "liebe" to "❤️", "lieb" to "🥰", "herz" to "❤️", "love" to "❤️", "ily" to "❤️", "hdl" to "❤️",
        "hdgdl" to "❤️", "kuss" to "😘", "küsschen" to "😘", "bussi" to "😘",
        "haha" to "😂", "hahaha" to "🤣", "lol" to "😂", "lach" to "😂", "witzig" to "😂", "lustig" to "😄",
        "freu" to "😊", "freue" to "😊", "freude" to "😊", "glücklich" to "😊", "super" to "👍",
        "ok" to "👌", "okay" to "👌", "gut" to "👍", "top" to "👍", "perfekt" to "👌", "genau" to "👍",
        "ja" to "👍", "nein" to "👎", "danke" to "🙏", "bitte" to "🙏", "dankeschön" to "🙏",
        "traurig" to "😢", "weinen" to "😭", "sorry" to "😔", "tut" to "😔", "schade" to "😕",
        "wütend" to "😠", "sauer" to "😡", "müde" to "😴", "schlafen" to "😴", "gute" to "😊",
        "krank" to "🤒", "cool" to "😎", "wow" to "😮", "krass" to "🤯", "oh" to "😮", "hmm" to "🤔",
        "denke" to "🤔", "überlege" to "🤔", "party" to "🎉", "feiern" to "🥳", "geburtstag" to "🎂",
        "glückwunsch" to "🎉", "gratuliere" to "🎉", "prost" to "🍻", "bier" to "🍺", "wein" to "🍷",
        "kaffee" to "☕", "essen" to "🍽️", "pizza" to "🍕", "burger" to "🍔", "kuchen" to "🍰",
        "hunger" to "😋", "lecker" to "😋", "sonne" to "☀️", "regen" to "🌧️", "schnee" to "❄️",
        "kalt" to "🥶", "heiß" to "🥵", "feuer" to "🔥", "geil" to "🔥", "stark" to "💪", "sport" to "💪",
        "fußball" to "⚽", "auto" to "🚗", "zug" to "🚆", "urlaub" to "🏖️", "flug" to "✈️",
        "zuhause" to "🏠", "hause" to "🏠", "arbeit" to "💼", "geld" to "💶", "hund" to "🐶",
        "katze" to "🐱", "blume" to "🌸", "blumen" to "💐", "baum" to "🌳", "weihnachten" to "🎄",
        "ostern" to "🐣", "geschenk" to "🎁", "musik" to "🎵", "telefon" to "📞", "handy" to "📱",
        "uhr" to "⏰", "zeit" to "⏰", "idee" to "💡", "stern" to "⭐", "hallo" to "👋", "hi" to "👋",
        "tschüss" to "👋", "ciao" to "👋", "servus" to "👋", "morgen" to "🌅", "nacht" to "🌙",
        "achtung" to "⚠️", "fertig" to "✅", "erledigt" to "✅", "check" to "✅", "fehler" to "❌",
        "schnell" to "⚡", "baby" to "👶", "frau" to "👩", "mann" to "👨", "familie" to "👪",
        "beten" to "🙏", "daumen" to "👍", "klatschen" to "👏", "bravo" to "👏", "applaus" to "👏",
        "augen" to "👀", "schau" to "👀", "geheim" to "🤫", "teufel" to "😈", "engel" to "😇",
        "kotzen" to "🤮", "eklig" to "🤢", "facepalm" to "🤦", "egal" to "🤷", "keine" to "🤷",
    )
}
