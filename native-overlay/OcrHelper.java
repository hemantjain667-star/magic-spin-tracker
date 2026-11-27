private void toggleLock() {
    if (scanBox == null || boxParams == null || wm == null) return;
    syncBoxXY();
    boxLock = !boxLock;
    if (boxLock) {
        boxParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    } else {
        boxParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }
    scanBox.setBackground(bg("#00000000", boxLock ? "#38BDF8" : "#22C55E", 2, 4));
    if (resizeHandle != null) resizeHandle.setVisibility(boxLock ? View.GONE : View.VISIBLE);
    wm.updateViewLayout(scanBox, boxParams);
    scanBox.post(this::syncBoxXY);
    savePositions();
    updateUI();
}
