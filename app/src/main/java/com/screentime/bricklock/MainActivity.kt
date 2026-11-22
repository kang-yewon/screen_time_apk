package com.screentime.bricklock

import android.app.NotificationManager
import android.content.ComponentName
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
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var btnStartLock: Button
    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrantDnd: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnGrantNotifListener: Button
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
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnGrantNotifListener = findViewById(R.id.btnGrantNotifListener)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)

        findViewById<Button>(R.id.btnPreset15m).setOnClickListener { setPreset(0, 15) }
        findViewById<Button>(R.id.btnPreset30m).setOnClickListener { setPreset(0, 30) }
        findViewById<Button>(R.id.btnPreset1h).setOnClickListener { setPreset(1, 0) }
        findViewById<Button>(R.id.btnPreset2h).setOnClickListener { setPreset(2, 0) }

        btnGrantDnd.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantNotifListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
                "[$formattedDuration] 동안 핸드폰이 벽돌 모드로 잠깁니다.\n\n" +
                "• 즉시 무음(방해금지) 상태로 전환됩니다.\n" +
                "• 다른 모든 앱 실행과 알림이 차단됩니다.\n" +
                "• 잠금화면 시계 확인과 전화 통화만 가능합니다.\n" +
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
        val dndGranted = isDndGranted()
        val accGranted = isAccessibilityGranted()
        val notifGranted = isNotificationListenerGranted()
        val overlayGranted = Settings.canDrawOverlays(this)

        btnGrantDnd.text = if (dndGranted) "1. 방해금지(무음) 권한 [완료]" else "1. 방해금지(무음) 권한 허용하기"
        btnGrantAccessibility.text = if (accGranted) "2. 앱 진입 차단(접근성) 권한 [완료]" else "2. 앱 진입 차단(접근성) 권한 허용하기"
        btnGrantNotifListener.text = if (notifGranted) "3. 알림 차단 권한 [완료]" else "3. 알림 차단 권한 허용하기"
        btnGrantOverlay.text = if (overlayGranted) "4. 다른 앱 위에 표시 권한 [완료]" else "4. 다른 앱 위에 표시 권한 허용하기"

        val allGranted = dndGranted && accGranted && notifGranted && overlayGranted
        tvPermStatus.text = if (allGranted) {
            "모든 필수 권한이 준비되었습니다."
        } else {
            "완벽한 벽돌 모드를 위해 아래 권한들을 허용해주세요."
        }
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isAccessibilityGranted(): Boolean {
        val expectedComponent = ComponentName(this, AppBlockAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any {
            ComponentName.unflattenFromString(it) == expectedComponent
        }
    }

    private fun isNotificationListenerGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }
}
