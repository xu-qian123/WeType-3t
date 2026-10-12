# Apply and restart feedback

The checkmark now waits for the settings persistence/synchronization callback
before restarting the IME. While applying, it disables repeated clicks and owns
one result toast; failed persistence does not start a restart operation.

The explicit ordered broadcast is the first restart channel and requires no
Root. On Android 14+, BroadcastOptions shares sender identity so the receiver's
existing package/UID validation can authenticate the request. The receiver
acknowledges a scheduled restart before its own process is killed, using a
500ms main-thread delay. Previously, missing sender identity or killing the
receiver before completing the broadcast could be misreported as module
inactivity. Root probing is now an IO-thread fallback only when no usable
acknowledgement is obtained. Unknown/missing acknowledgements do not establish
anything about LSPosed activation or Root authorization.
The receiver is unregistered when its module generation is retired and is
registered against ActivityThread.currentApplication during hot reload, since
Application.attach is not replayed. An eight-second acknowledgement window
also covers a cold IME startup before any Root fallback is attempted.

Chinese feedback is now either "微信输入法已重启" or
"设置已保存，请手动重启微信输入法". A failed save remains a save error.
When a successful Root process query confirms that no IME process exists, the
result is NotRunning and the toast is "设置已保存，下次打开微信输入法生效".
Query failures, denied Root and unknown acknowledgements never count as evidence
that the process is absent. This distinguishes consecutive applications on
settings pages from a failed attempt to restart a running input method.

Device verification exercised the non-Root receiver path: an authenticated
ordered-broadcast reply had code=-1, data=scheduled and active=true, followed by
IME process exit and a newly registered receiver in the replacement process.
The user confirmed that the non-Root flow worked. The signed release build and
104 JVM tests passed; the newly distinguished NotRunning UI awaits user feedback.
