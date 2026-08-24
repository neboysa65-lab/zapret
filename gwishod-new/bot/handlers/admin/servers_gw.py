from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.error import BadRequest
from telegram.ext import ContextTypes
import re

from bot.services.api_client import api
from bot.utils.auth import admin_only
from bot.utils.format import esc, code, field

# ────────────────────────────────────────────────────────────────────────────
#  Мастер добавления GW-сервера (пошаговый ввод всех полей)
# ────────────────────────────────────────────────────────────────────────────
#
# Шаги задаются списком ниже. Значение "-" означает «оставить значение по
# умолчанию / пропустить». Состояние хранится в context.user_data:
#   waiting_for = "server_gw_add"
#   gw_new      = {"step": <индекс>, "data": {...собранные поля...}}
#
_GW_STEPS = [
    # (ключ, prompt, default, validator)
    ("ip_address",
     "🌍 <b>SSH-хост или IP ноды</b>\n\nНапример: <code>1.2.3.4</code> или <code>node-uk1.example.com</code>",
     None, "ip"),
    ("ssh_port",
     "🔌 <b>SSH-порт</b>\n\nВведите число или <code>-</code> для порта по умолчанию (22)",
     22, "port"),
    ("ssh_username",
     "👤 <b>SSH-пользователь</b>\n\nВведите имя или <code>-</code> для значения по умолчанию (<code>gw</code>)",
     "gw", "str"),
    ("ssh_password",
     "🔑 <b>SSH-пароль</b> (от пользователя gw на ноде)\n\nОбязателен — без него клиенты не подключатся.",
     None, "req"),
    ("proxy_host",
     "🌐 <b>Прокси/CDN-фронт</b> (host перед SSH)\n\nДомен или <code>-</code>, если подключение прямое (без прокси)",
     "", "str"),
    ("proxy_port",
     "🔌 <b>Порт прокси</b>\n\nЧисло или <code>-</code> для значения по умолчанию (80)",
     80, "port"),
    ("proxy_scheme",
     "🔒 <b>Схема прокси</b>: <code>http</code> или <code>https</code>\n\n<code>-</code> = http",
     "http", "scheme"),
    ("sni",
     "🕵️ <b>SNI</b> для TLS-фронтирования\n\nДомен или <code>-</code> (не используется)",
     "", "str"),
    ("payload",
     "📨 <b>Payload</b> (HTTP-запрос для прохода через прокси/CDN)\n\n"
     "Можно в несколько строк. Токены: [host] [port] [ua] [crlf] [cr] [lf] "
     "[method] [protocol] [ssh] [host_port] [split]\n\n"
     "<code>-</code> = встроенный шаблон клиента (websocket upgrade)",
     "", "raw"),
    ("country",
     "🏳️ <b>Страна</b> (название в списке серверов)\n\nНапример: <code>Germany</code>",
     None, "req"),
    ("state",
     "🏙 <b>Город/регион</b>\n\nНапример: <code>Frankfurt am Main</code>. <code>-</code> = пусто",
     "", "str"),
    ("country_code",
     "🔤 <b>Код страны</b> (для флажка в приложении)\n\n"
     "Двухбуквенный ISO-код строчными: <code>de</code>, <code>us</code>, <code>ru</code>, "
     "<code>fi</code>, <code>pl</code>, <code>nl</code>…\n"
     "Особые флаги: <code>northern_cyprus</code>, <code>somaliland</code>, "
     "<code>south_ossetia</code>, <code>transnistria</code>, <code>tibet</code>, "
     "<code>united_nations</code>, <code>flag_usa</code>\n\n"
     "⚠️ Неверный код = вместо флажка будет глобус",
     "", "cc"),
    ("priority",
     "📌 <b>Приоритет</b> (меньше — выше в списке)\n\nЧисло или <code>-</code> (=0)",
     0, "int"),
    ("premium",
     "💎 <b>Премиум?</b> (<code>да</code>/<code>нет</code>)",
     False, "bool"),
    ("recommend",
     "👍 <b>Рекомендуемый?</b> (<code>да</code>/<code>нет</code>)",
     False, "bool"),
]


def _gw_kb_cancel() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([[InlineKeyboardButton("✖️ Отмена", callback_data="gw_add_cancel")]])


def _parse_step(validator: str, raw: str, default):
    """Возвращает (ok, value). raw уже stripped."""
    if validator == "ip":
        if not raw or " " in raw:
            return False, None
        return True, raw
    if validator == "req":
        if not raw:
            return False, None
        return True, raw
    if validator in ("port", "int"):
        try:
            v = int(raw)
        except ValueError:
            return False, None
        if validator == "port" and not (1 <= v <= 65535):
            return False, None
        return True, v
    if validator == "scheme":
        v = raw.lower()
        if v not in ("http", "https"):
            return False, None
        return True, v
    if validator == "cc":
        # Код страны для флажка: ISO a-z (2 символа) или особое имя из resFlags
        # (northern_cyprus, flag_usa, ...). Всё в нижний регистр.
        v = raw.lower().strip()
        if not re.fullmatch(r"[a-z][a-z0-9_]{1,19}", v):
            return False, None
        return True, v
    if validator == "bool":
        v = raw.lower()
        if v in ("да", "yes", "y", "1", "true"):
            return True, True
        if v in ("нет", "no", "n", "0", "false"):
            return True, False
        return False, None
    if validator == "raw":
        # многострочный payload; "-" → пусто (клиент возьмёт встроенный шаблон)
        return True, "" if raw == "-" else raw
    # str: "-" → default/пусто
    if raw == "-":
        return True, default if isinstance(default, str) else ""
    return True, raw


@admin_only
async def gw_server_create_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Кнопка «➕ Добавить сервер» — запуск мастера."""
    query = update.callback_query
    await query.answer()
    context.user_data["waiting_for"] = "server_gw_add"
    context.user_data["gw_new"] = {"step": 0, "data": {}}

    key, prompt, _default, _validator = _GW_STEPS[0]
    text = (
        f"➕ <b>Добавление GW-сервера</b> · шаг 1/{len(_GW_STEPS)}\n"
        f"━━━━━━━━━━━━━━━\n\n{prompt}\n\n"
        f"✖️ Отмена — кнопкой ниже или командой /cancel"
    )
    await query.edit_message_text(text, reply_markup=_gw_kb_cancel(), parse_mode="HTML")


@admin_only
async def gw_server_create_cancel(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer("Отменено")
    context.user_data.pop("waiting_for", None)
    context.user_data.pop("gw_new", None)
    query.data = "servers:gw"
    await servers_gw(update, context)


@admin_only
async def gw_server_create_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    state = context.user_data.get("gw_new") or {}
    step = int(state.get("step", 0))
    data = dict(state.get("data", {}))
    raw = (update.message.text or "").strip()

    if raw.lower() in ("/cancel", "/cancel@start"):
        context.user_data.pop("waiting_for", None)
        context.user_data.pop("gw_new", None)
        await update.message.reply_text("❌ Добавление сервера отменено")
        return

    key, prompt, default, validator = _GW_STEPS[step]
    ok, value = _parse_step(validator, raw, default)
    if not ok:
        await update.message.reply_html(
            f"❌ Некорректное значение для <b>{esc(key)}</b>. Попробуйте ещё раз "
            f"(или <code>-</code> для значения по умолчанию):",
            reply_markup=_gw_kb_cancel(),
        )
        return

    data[key] = value

    # Есть ли следующие шаги?
    if step + 1 < len(_GW_STEPS):
        context.user_data["gw_new"] = {"step": step + 1, "data": data}
        nkey, nprompt, _ndefault, _nvalidator = _GW_STEPS[step + 1]
        await update.message.reply_html(
            f"✅ {esc(key)} = <code>{esc(str(value))[:80]}</code>\n"
            f"━━━━━━━━━━━━━━━\n"
            f"➕ <b>Добавление GW-сервера</b> · шаг {step + 2}/{len(_GW_STEPS)}\n\n{nprompt}",
            reply_markup=_gw_kb_cancel(),
        )
        return

    # Все шаги пройдены — создаём сервер одним запросом
    context.user_data.pop("waiting_for", None)
    context.user_data.pop("gw_new", None)

    result = await api.post("/vpn/api/v1/bot/servers_gw/create", data=data)
    ip = data.get("ip_address", "")
    if result.get("success") == 1:
        await update.message.reply_html(
            f"🎉 <b>GW-сервер создан:</b> {code(ip)}\n"
            f"Он появится у пользователей после следующего обновления списка серверов.",
            reply_markup=InlineKeyboardMarkup([[
                InlineKeyboardButton("⬅️ К списку GW", callback_data="servers:gw"),
                InlineKeyboardButton(f"🌐 Карточка", callback_data=f"gw_server:{ip}"),
            ]]),
        )
    else:
        kb = [[InlineKeyboardButton("🔁 Начать заново", callback_data="gw_add")],
              [InlineKeyboardButton("⬅️ К списку", callback_data="servers:gw")]]
        await update.message.reply_html(
            f"❌ Не удалось создать сервер: {esc(result.get('message', 'unknown'))}",
            reply_markup=InlineKeyboardMarkup(kb),
        )


async def _safe_edit(query, text: str, reply_markup=None):
    try:
        await query.edit_message_text(text, reply_markup=reply_markup, parse_mode="HTML")
    except BadRequest as e:
        if "Message is not modified" not in str(e):
            raise


@admin_only
async def servers_gw(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    data = await api.get("/vpn/api/v1/bot/servers_gw/all")
    servers = data.get("data", [])
    if not servers:
        kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("➕ Добавить сервер", callback_data="gw_add")],
            [InlineKeyboardButton("⬅️ Назад", callback_data="menu:servers")],
        ])
        await _safe_edit(query, "🌐 <b>GW</b>\n\nСерверов пока нет.", reply_markup=kb)
        return

    active = sum(1 for s in servers if s.get("status"))
    text = (
        f"🌐 <b>GW — серверы</b>\n"
        f"━━━━━━━━━━━━━━━\n"
        f"Всего: <b>{len(servers)}</b> · Активных: <b>{active}</b>\n\n"
        f"Нажмите на сервер, чтобы открыть карточку:"
    )
    keyboard = []
    for s in servers:
        ip = s.get("ip_address", "unknown")
        country = s.get("country", "Unknown")
        status = "🟢" if s.get("status") else "🔴"
        prem = " ⭐" if s.get("premium") else ""
        keyboard.append([InlineKeyboardButton(f"{status} {country} · {ip}{prem}", callback_data=f"gw_server:{ip}")])

    keyboard.append([
        InlineKeyboardButton("➕ Добавить", callback_data="gw_add"),
        InlineKeyboardButton("🔄 Обновить", callback_data="servers:gw"),
    ])
    keyboard.append([InlineKeyboardButton("⬅️ Назад", callback_data="menu:servers")])
    await _safe_edit(query, text, reply_markup=InlineKeyboardMarkup(keyboard))


@admin_only
async def gw_server_info(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    ip = query.data.split(":", 1)[1]

    data = await api.get("/vpn/api/v1/bot/servers_gw/get", params={"server_ip": ip})
    server = data.get("data")
    if not server:
        await _safe_edit(
            query,
            "❌ Сервер не найден",
            reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("⬅️ Назад", callback_data="servers:gw")]]),
        )
        return

    status = "🟢 Активен" if server.get("status") else "🔴 Выключен"
    premium = "⭐ Платный" if server.get("premium") else "🆓 Бесплатный"
    recommend = "✅ Да" if server.get("recommend") else "—"
    proxy_host = field(server.get("proxy_host"), "—")
    proxy_port = server.get("proxy_port", 80)
    payload_preview = (server.get("payload") or "")[:60]
    payload_line = esc(payload_preview) + ("…" if len(server.get("payload") or "") > 60 else "")
    text = (
        f"🌐 <b>GW · {esc(field(server.get('country')))}</b>\n"
        f"━━━━━━━━━━━━━━━\n"
        f"🌍 SSH-хост: {code(server.get('ip_address', 'N/A'))}\n"
        f"🔌 SSH-порт: {esc(server.get('ssh_port', 22))}\n"
        f"👤 SSH-юзер: {esc(field(server.get('ssh_username'), 'gw'))}\n"
        f"🌐 Прокси (CDN): {esc(proxy_host)}:{esc(proxy_port)} ({esc(field(server.get('proxy_scheme'), 'http'))})\n"
        f"🔒 SNI: {esc(field(server.get('sni'), '—'))}\n"
        f"📨 Payload: {payload_line or '—'}\n"
        f"🏳️ Страна: {esc(field(server.get('country')))}\n"
        f"🏙 Город: {esc(field(server.get('state')))}\n"
        f"🔤 Код страны: {esc(field(server.get('country_code'), '?'))}\n"
        f"📶 Статус: {status}\n"
        f"💎 Тип: {premium}\n"
        f"📌 Приоритет: {esc(server.get('priority', 0))}\n"
        f"👍 Рекомендуемый: {recommend}"
    )

    toggle_text = "🔴 Выключить" if server.get("status") else "🟢 Включить"
    kb = [
        [
            InlineKeyboardButton(toggle_text, callback_data=f"gw_toggle:{ip}"),
            InlineKeyboardButton("⭐ Прем вкл/выкл", callback_data=f"gw_prem:{ip}"),
        ],
        [
            InlineKeyboardButton("✏️ Страна/имя", callback_data=f"gw_edit:{ip}"),
            InlineKeyboardButton("🏙 Город", callback_data=f"gw_edit_city:{ip}"),
            InlineKeyboardButton("🔤 Флаг", callback_data=f"gw_edit_cc:{ip}"),
        ],
        [InlineKeyboardButton("📨 Payload", callback_data=f"gw_edit_payload:{ip}")],
        [InlineKeyboardButton("🗑 Удалить", callback_data=f"gw_del:{ip}")],
        [InlineKeyboardButton("⬅️ К списку", callback_data="servers:gw")],
    ]
    await _safe_edit(query, text, reply_markup=InlineKeyboardMarkup(kb))


@admin_only
async def gw_server_toggle(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    ip = query.data.split(":", 1)[1]

    data = await api.get("/vpn/api/v1/bot/servers_gw/get", params={"server_ip": ip})
    server = data.get("data")
    if server:
        new_status = not server.get("status")
        await api.post("/vpn/api/v1/bot/servers_gw/update", data={"ip_address": ip, "status": new_status})
        await query.answer("✅ Статус изменён")

    query.data = f"gw_server:{ip}"
    await gw_server_info(update, context)


@admin_only
async def gw_server_prem(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    ip = query.data.split(":", 1)[1]

    data = await api.get("/vpn/api/v1/bot/servers_gw/get", params={"server_ip": ip})
    server = data.get("data")
    if server:
        new_prem = not server.get("premium")
        await api.post("/vpn/api/v1/bot/servers_gw/update", data={"ip_address": ip, "premium": new_prem})
        await query.answer("✅ Тип изменён")
    query.data = f"gw_server:{ip}"
    await gw_server_info(update, context)


@admin_only
async def gw_server_del(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    ip = query.data.split(":", 1)[1]
    await api.post("/vpn/api/v1/bot/servers_gw/delete", data={"ip_address": ip})

    await query.answer("🗑 Удалено", show_alert=True)
    query.data = "servers:gw"
    await servers_gw(update, context)


@admin_only
async def gw_server_edit_prompt(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    ip = query.data.split(":", 1)[1]
    context.user_data["waiting_for"] = "server_gw_edit"
    context.user_data["edit_server_gw_ip"] = ip
    await _safe_edit(
        query,
        f"✏️ <b>Изменение имени GW-сервера</b> {code(ip)}\n\nОтправьте новое <b>имя (название страны)</b> сообщением:",
        reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("✖️ Отмена", callback_data=f"gw_server:{ip}")]]),
    )


@admin_only
async def gw_server_edit_city_prompt(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    ip = query.data.split(":", 1)[1]
    context.user_data["waiting_for"] = "server_gw_edit_city"
    context.user_data["edit_server_gw_ip"] = ip
    await _safe_edit(
        query,
        f"🏙 <b>Изменение города (региона) GW-сервера</b> {code(ip)}\n\nОтправьте новое <b>название города/региона</b> сообщением:",
        reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("✖️ Отмена", callback_data=f"gw_server:{ip}")]]),
    )


@admin_only
async def gw_server_edit_payload_prompt(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    ip = query.data.split(":", 1)[1]
    context.user_data["waiting_for"] = "server_gw_edit_payload"
    context.user_data["edit_server_gw_ip"] = ip
    await _safe_edit(
        query,
        f"📨 <b>Изменение Payload GW-сервера</b> {code(ip)}\n\n"
        f"Отправьте новый <b>payload</b> сообщением.\n\n"
        f"Поддерживаемые токены: [host] [port] [ua] [crlf] [cr] [lf] [method] [protocol] "
        f"[ssh] [host_port] [split]\n\n"
        f"Новый payload применится ко всем пользователям сервера сразу.",
        reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("✖️ Отмена", callback_data=f"gw_server:{ip}")]]),
    )


@admin_only
async def gw_server_edit_cc_prompt(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Смена кода страны = смена флажка в приложении."""
    query = update.callback_query
    await query.answer()
    ip = query.data.split(":", 1)[1]
    context.user_data["waiting_for"] = "server_gw_edit_cc"
    context.user_data["edit_server_gw_ip"] = ip
    await _safe_edit(
        query,
        f"🔤 <b>Смена флажка GW-сервера</b> {code(ip)}\n\n"
        f"Отправьте <b>код страны</b> сообщением — двухбуквенный ISO строчными "
        f"(<code>de</code>, <code>us</code>, <code>ru</code>, <code>fi</code>…).\n"
        f"Особые флаги: <code>northern_cyprus</code>, <code>somaliland</code>, "
        f"<code>south_ossetia</code>, <code>transnistria</code>, <code>tibet</code>, "
        f"<code>united_nations</code>, <code>flag_usa</code>.\n\n"
        f"⚠️ Неверный код = вместо флажка глобус (краша больше не будет).",
        reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("✖️ Отмена", callback_data=f"gw_server:{ip}")]]),
    )


async def gw_server_edit_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    waiting_for = context.user_data.get("waiting_for")
    if waiting_for not in [
        "server_gw_edit",
        "server_gw_edit_city",
        "server_gw_edit_payload",
        "server_gw_edit_cc",
    ]:
        return

    new_val = update.message.text.strip() if update.message.text else ""
    if waiting_for == "server_gw_edit_payload" and not new_val:
        await update.message.reply_text("❌ Payload не может быть пустым")
        return
    if not new_val:
        await update.message.reply_text("❌ Пустое значение")
        return

    if waiting_for == "server_gw_edit_cc":
        import re as _re
        cc = new_val.lower()
        if not _re.fullmatch(r"[a-z][a-z0-9_]{1,19}", cc):
            await update.message.reply_html(
                "❌ Код страны должен быть из строчных латинских букв (2 символа, "
                "напр. <code>de</code>) или особым именем флага "
                "(<code>northern_cyprus</code>, <code>flag_usa</code>…). Попробуйте ещё раз:",
            )
            return
        new_val = cc

    ip = context.user_data.get("edit_server_gw_ip", "")

    context.user_data.pop("waiting_for", None)
    context.user_data.pop("edit_server_gw_ip", None)

    if waiting_for == "server_gw_edit":
        result = await api.post("/vpn/api/v1/bot/servers_gw/update", data={"ip_address": ip, "country": new_val})
        msg_success = "✅ Имя GW-сервера обновлено!"
    elif waiting_for == "server_gw_edit_payload":
        result = await api.post("/vpn/api/v1/bot/servers_gw/update", data={"ip_address": ip, "payload": new_val})
        msg_success = "✅ Payload GW-сервера обновлён! Применён ко всем пользователям."
    elif waiting_for == "server_gw_edit_cc":
        result = await api.post("/vpn/api/v1/bot/servers_gw/update", data={"ip_address": ip, "country_code": new_val})
        msg_success = f"✅ Флажок обновлён: {new_val}. Появится у пользователей после перезапуска списка серверов."
    else:
        result = await api.post("/vpn/api/v1/bot/servers_gw/update", data={"ip_address": ip, "state": new_val})
        msg_success = "✅ Город (регион) GW-сервера обновлён!"

    if result.get("success") == 1:
        kb = InlineKeyboardMarkup([[InlineKeyboardButton("⬅️ К серверу", callback_data=f"gw_server:{ip}")]])
        await update.message.reply_html(msg_success, reply_markup=kb)
    else:
        await update.message.reply_text(f"❌ {result.get('message', 'unknown')}")
