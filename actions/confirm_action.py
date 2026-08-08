# actions/confirm_action.py
# Confirms or cancels a pending destructive action requested by another
# tool (e.g. file_controller's delete, open_app's close). See safety.py.

import safety


def confirm_action(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        action: "confirm" | "cancel" (required)
        token: string (required) — the short token given in the original
               "confirmation needed" message
    """
    params = parameters or {}
    action = params.get("action", "confirm").lower().strip()
    token  = params.get("token", "").strip()

    if not token:
        return "Please provide the confirmation token, sir."

    if action == "cancel":
        result = safety.cancel(token)
    else:
        result = safety.confirm(token)

    if player:
        player.write_log(f"[confirm_action] {action} {token}: {result[:60]}")

    return result
