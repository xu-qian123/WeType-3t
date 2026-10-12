# Candidate pinyin display mode

The device's WeType 4.0 native preedit-to-screen mode clears ImeStrikeTextView
and hides strike_container_rl. Padding alone cannot make that text visible.
Static inspection confirms pendinginput.a.t() selects all-scene preedit and
u(int) selects search/go preedit. The new appearance setting preserves native
decisions by default. Choosing candidate-bar pinyin returns false from those
two verified boolean methods, allowing the host's normal candidate pinyin path
to populate and display the row. Old versions without this class are unchanged.

The candidate_pinyin_in_bar boolean is saved with the settings snapshot, local
preferences and remote bundle, preserved by partial updates, and included by
the existing scalar backup mechanism. Reset returns to native behavior. The
left-margin editor is shown only for candidate-bar mode. Clear unconfirmed input
and reopen the keyboard after saving a mode switch to avoid retaining an old
composition session. The native option follows the input method's own settings;
it does not forcibly enable a feature unavailable on an older version.

The signed release and existing 104 JVM tests passed. The user confirmed that
candidate-bar pinyin appeared after choosing that mode and saving. The helper
description uses the existing body2/onSurfaceVariantSummary presentation.
