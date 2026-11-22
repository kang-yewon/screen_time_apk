package com.screentime.bricklock

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var btnStartLock: Button
    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrantDnd: Button
    private lateinit var btnGrantOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (LockManager.isLocked(this)) {
            startActivity(Intent(this, LockScreenActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        etHours = findViewById(R.id.etHours)
        etMinutes = findViewById(R.id.etMinutes)
        btnStartLock = findViewById(R.id.btnStartLock)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        btnGrantDnd = findViewById(R.id.btnGrantDnd)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)

        findViewById<Button>(R.id.btnPreset15m).setOnClickListener { setPreset(0, 15) }
        findViewById<Button>(R.id.btnPreset30m).setOnClickListener { setPreset(0, 30) }
        findViewById<Button>(R.id.btnPreset1h).setOnClickListener { setPreset(1, 0) }
        findViewById<Button>(R.id.btnPreset2h).setOnClickListener { setPreset(2, 0) }

        btnGrantDnd.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStartLock.setOnClickListener {
            onStartLockClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        if (LockManager.isLocked(this)) {
            startActivity(Intent(this, LockScreenActivity::class.java))
            finish()
            return
        }
        updatePermissionUI()
    }

    private fun setPreset(hours: Int, minutes: Int) {
        etHours.setText(hours.toString())
        etMinutes.setText(minutes.toString())
    }

    private fun onStartLockClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 [다른 앱 위에 표시 권한]을 허용해주세요.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val hours = etHours.text.toString().toIntOrNull() ?: 0
        val minutes = etMinutes.text.toString().toIntOrNull() ?: 0
        val totalMinutes = hours * 60 + minutes

        if (totalMinutes <= 0) {
            Toast.makeText(this, "잠글 시간을 1분 이상 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val durationMillis = totalMinutes * 60_000L
        val formattedDuration = buildString {
            if (hours > 0) append("${hours}시간 ")
            if (minutes > 0) append("${minutes}분")
        }.trim()

        AlertDialog.Builder(this, R.style.AlertDialogDark)
            .setTitle("경고: 진짜 하시겠습니까?")
            .setMessage(
                "[$formattedDuration] 동안 핸드폰 화면이 잠깁니다.\n\n" +
                "• 화면 전체에 시계 잠금화면이 고정되어 홈 버튼이나 다른 앱으로 나갈 수 없습니다.\n" +
                "• 즉시 무음(방해금지) 상태로 전환되며 알림 팝업이 차단됩니다.\n" +
                "• 오직 시계 확인과 전화 통화만 가능합니다.\n" +
                "• 시간이 끝날 때까지 절대 해제할 수 없습니다.\n\n" +
                "정말 실행하시겠습니까?"
            )
            .setCancelable(true)
            .setNegativeButton("취소", null)
            .setPositiveButton("실행") { _, _ ->
                LockManager.startLock(this, durationMillis)
                val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(lockIntent)
                finish()
            }
            .show()
    }

    private fun updatePermissionUI() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val dndGranted = isDndGranted()

        btnGrantOverlay.text = if (overlayGranted) "1. 화면 고정(다른 앱 위에 표시) 권한 [완료]" else "1. 화면 고정(다른 앱 위에 표시) 권한 허용하기"
        btnGrantDnd.text = if (dndGranted) "2. 방해금지(무음/알림차단) 권한 [완료]" else "2. 방해금지(무음/알림차단) 권한 허용하기"

        val allGranted = overlayGranted && dndGranted
        tvPermStatus.text = if (allGranted) {
            "모든 권한이 준비되었습니다. 바로 잠금을 실행할 수 있습니다."
        } else {
            "접근성 권한 없이 아래 2개 권한만으로 완벽히 잠급니다."
        }
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }
}
