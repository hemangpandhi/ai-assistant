package com.tcs.vehicleassistant.handlers

/**
 * Pure volume arithmetic for [MediaToolHandler.setVolumeLevel].
 * Keeps relative "up"/"down" as stream-index steps (not absolute 5%).
 */
object VolumeLevelResolver {

    data class Plan(
        val targetIndex: Int,
        val previousIndex: Int,
        val maxIndex: Int,
        val relative: Boolean,
        val increasing: Boolean?,
    ) {
        val previousPct: Int get() = toPercentage(previousIndex, maxIndex)
        val targetPct: Int get() = toPercentage(targetIndex, maxIndex)
    }

    /** ~5% of max stream index, minimum one hardware step. */
    fun relativeStep(maxIndex: Int): Int {
        val max = maxIndex.coerceAtLeast(0)
        if (max <= 0) return 0
        return maxOf(1, Math.round(max * 0.05f))
    }

    fun toPercentage(index: Int, maxIndex: Int): Int {
        val max = maxIndex.coerceAtLeast(1)
        val clamped = index.coerceIn(0, max)
        return Math.round((clamped.toFloat() / max) * 100)
    }

    /**
     * @param arg raw tool argument (e.g. "up", "down", "50%", "+10%", "MAX", "3")
     * @param toolCall full call string; used only as a decrease hint when arg is empty/ambiguous
     */
    fun plan(arg: String, currentIndex: Int, maxIndex: Int, toolCall: String = ""): Plan {
        val maxVol = maxIndex.coerceAtLeast(0)
        val curVol = currentIndex.coerceIn(0, maxVol)
        val argStr = arg.trim()

        if (argStr.isBlank() || argStr.equals("UP", ignoreCase = true) || argStr.equals("DOWN", ignoreCase = true)) {
            val isDecrease = argStr.equals("DOWN", ignoreCase = true) ||
                toolCall.contains("decrease", ignoreCase = true)
            val step = relativeStep(maxVol)
            val target = if (isDecrease) {
                (curVol - step).coerceAtLeast(0)
            } else {
                (curVol + step).coerceAtMost(maxVol)
            }
            return Plan(target, curVol, maxVol, relative = true, increasing = !isDecrease)
        }

        if (argStr.equals("MAX", ignoreCase = true)) {
            return Plan(maxVol, curVol, maxVol, relative = false, increasing = maxVol > curVol)
        }

        if (argStr.startsWith("+") || argStr.startsWith("-")) {
            val hasPercentSign = argStr.contains("%")
            val parsedNum = argStr.replace("%", "").toIntOrNull() ?: 0
            val delta = if (hasPercentSign || Math.abs(parsedNum) > maxVol) {
                Math.round((parsedNum / 100f) * maxVol)
            } else {
                parsedNum
            }
            val target = (curVol + delta).coerceIn(0, maxVol)
            return Plan(
                target,
                curVol,
                maxVol,
                relative = true,
                increasing = when {
                    delta > 0 -> true
                    delta < 0 -> false
                    else -> null
                },
            )
        }

        val hasPercentSign = argStr.contains("%")
        val parsedNum = argStr.replace("%", "").replace("+", "").toIntOrNull()
        if (parsedNum != null) {
            val target = if (hasPercentSign || parsedNum > maxVol) {
                Math.round((parsedNum / 100f) * maxVol)
            } else {
                parsedNum
            }.coerceIn(0, maxVol)
            return Plan(
                target,
                curVol,
                maxVol,
                relative = false,
                increasing = when {
                    target > curVol -> true
                    target < curVol -> false
                    else -> null
                },
            )
        }

        // Unknown token: treat as a single relative step (increase unless toolCall says decrease).
        val isDecrease = toolCall.contains("decrease", ignoreCase = true)
        val step = relativeStep(maxVol)
        val target = if (isDecrease) {
            (curVol - step).coerceAtLeast(0)
        } else {
            (curVol + step).coerceAtMost(maxVol)
        }
        return Plan(target, curVol, maxVol, relative = true, increasing = !isDecrease)
    }

    /**
     * Spoken feedback after AudioManager apply + readback.
     * Prefer the actual applied index so we never claim a level we did not reach.
     */
    fun feedback(plan: Plan, appliedIndex: Int): String {
        val applied = appliedIndex.coerceIn(0, plan.maxIndex.coerceAtLeast(0))
        val pct = toPercentage(applied, plan.maxIndex)
        val changed = applied != plan.previousIndex

        if (!changed) {
            return when {
                plan.increasing == true && applied >= plan.maxIndex ->
                    "Volume is already at maximum ($pct%)."
                plan.increasing == false && applied <= 0 ->
                    "Volume is already at minimum ($pct%)."
                plan.targetIndex != plan.previousIndex ->
                    "I couldn't change the volume; it's still at $pct%."
                else -> "Volume is already at $pct%."
            }
        }

        return when {
            plan.relative && plan.increasing == true ->
                "I've increased the volume to $pct%."
            plan.relative && plan.increasing == false ->
                "I've decreased the volume to $pct%."
            else -> "I've set the volume to $pct%."
        }
    }
}
