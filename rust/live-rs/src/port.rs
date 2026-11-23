//! The Rust port of `app/src/main/java/com/cleanroom/stadiumdsp/live/`.
//!
//! Bit-exact JVM semantics (`wrapping_add`/`wrapping_sub` matching Kotlin `Long` overflow,
//! truncating division matching JVM `/`, zero `unsafe` code).

#![forbid(unsafe_code)]

/// Port of `computeStopwatchElapsedMs` (`app/src/main/java/com/cleanroom/stadiumdsp/live/LiveTimingMath.kt:8`).
pub fn compute_stopwatch_elapsed_ms(
    accumulated_ms: i64,
    start_epoch_ms: i64,
    is_running: bool,
    now_ms: i64,
) -> Result<i64, String> {
    let active = if is_running {
        now_ms.wrapping_sub(start_epoch_ms).max(0)
    } else {
        0
    };
    Ok(accumulated_ms.wrapping_add(active))
}

/// Port of `formatStopwatchMmSs` (`app/src/main/java/com/cleanroom/stadiumdsp/live/LiveTimingMath.kt:18`).
pub fn format_stopwatch_mm_ss(elapsed_ms: i64) -> Result<String, String> {
    let total_sec = (elapsed_ms / 1000).max(0);
    let mins = total_sec / 60;
    let secs = total_sec % 60;
    Ok(format!("{mins:02}:{secs:02}"))
}

/// Port of `countTriggeredCues` (`app/src/main/java/com/cleanroom/stadiumdsp/live/LiveTimingMath.kt:27`).
pub fn count_triggered_cues(
    cue_timestamps_ms: Vec<i64>,
    next_cue_idx: i64,
    new_position_ms: i64,
) -> Result<i64, String> {
    let pos = new_position_ms.max(0);
    let mut idx = usize::try_from(next_cue_idx.max(0)).unwrap_or(usize::MAX);
    let mut count: i64 = 0;
    while idx < cue_timestamps_ms.len() && cue_timestamps_ms[idx] <= pos {
        count += 1;
        idx += 1;
    }
    Ok(count)
}

/// Port of `findNextCueRemainingMs` (`app/src/main/java/com/cleanroom/stadiumdsp/live/LiveTimingMath.kt:42`).
pub fn find_next_cue_remaining_ms(
    cue_timestamps_ms: Vec<i64>,
    next_cue_idx: i64,
    current_position_ms: i64,
) -> Result<i64, String> {
    if next_cue_idx < 0 {
        return Ok(-1);
    }
    let Ok(idx) = usize::try_from(next_cue_idx) else {
        return Ok(-1);
    };
    let Some(&cue_ts) = cue_timestamps_ms.get(idx) else {
        return Ok(-1);
    };
    let pos = current_position_ms.max(0);
    Ok(cue_ts.wrapping_sub(pos).max(0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stopwatch_elapsed_and_formatting_match_jvm() {
        assert_eq!(
            compute_stopwatch_elapsed_ms(10_000, 5_000, true, 12_500).unwrap(),
            17_500
        );
        assert_eq!(
            compute_stopwatch_elapsed_ms(10_000, 5_000, false, 12_500).unwrap(),
            10_000
        );
        assert_eq!(
            compute_stopwatch_elapsed_ms(10_000, 15_000, true, 12_500).unwrap(),
            10_000
        );
        assert_eq!(format_stopwatch_mm_ss(-5000).unwrap(), "00:00");
        assert_eq!(format_stopwatch_mm_ss(0).unwrap(), "00:00");
        assert_eq!(format_stopwatch_mm_ss(65_999).unwrap(), "01:05");
        assert_eq!(format_stopwatch_mm_ss(6_120_000).unwrap(), "102:00");
    }

    #[test]
    fn cue_counting_and_remaining_ms_match_jvm() {
        let cues = vec![0, 8_000, 16_000, 24_000, 34_000];
        assert_eq!(count_triggered_cues(cues.clone(), 0, 16_000).unwrap(), 3);
        assert_eq!(count_triggered_cues(cues.clone(), 3, 20_000).unwrap(), 0);
        assert_eq!(count_triggered_cues(cues.clone(), -2, 8_000).unwrap(), 2);
        assert_eq!(
            find_next_cue_remaining_ms(cues.clone(), 2, 10_500).unwrap(),
            5_500
        );
        assert_eq!(
            find_next_cue_remaining_ms(cues.clone(), 10, 10_500).unwrap(),
            -1
        );
        assert_eq!(find_next_cue_remaining_ms(cues, -1, 10_500).unwrap(), -1);
    }
}
