"""Appwrite Functions scheduled entrypoint for background jobs."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import asyncio

from src.logger import init_logger, init_panic_handler

import asyncio

from src.logger import init_logger, init_panic_handler


async def main(context):
    init_panic_handler()
    init_logger()

    async def _run_all_ticks():
        # Imported lazily so a failure in one module never blocks the
        # others; every tick catches its own exceptions internally.
        from src.utils.premium_unloader import (
            recreate_users_pass,
            unload_premium_tick,
            unload_premium_v3_tick,
            unload_premium_vless_tick,
        )
        from src.utils.notificator import periodic_task_tick
        from src.old_deleter import delete_users_tick
        from src.utils.server_metrics import (
            load_state,
            save_state,
            update_server_metrics_tick,
        )

        load_state()
        await unload_premium_tick()
        await unload_premium_v3_tick()
        await unload_premium_vless_tick()
        await recreate_users_pass()
        await periodic_task_tick()
        await delete_users_tick()
        await update_server_metrics_tick()
        save_state()

    try:
        await _run_all_ticks()
        context.log("Cron pass completed")
    except Exception as exc:
        context.error(f"Cron pass failed: {exc!r}")
        return context.res.empty()

    return context.res.empty()
