package com.screentime.bricklock

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var btnStartLock: Button
    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantDnd: Button
    private lateinit var btnGrantUsage: Button
    private lateinit var btnGrantCall: Button

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
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantDnd = findViewById(R.id.btnGrantDnd)
        btnGrantUsage = findViewById(R.id.btnGrantUsage)
        btnGrantCall = findViewById(R.id.btnGrantCall)

        findViewById<Button>(R.id.btnPreset15m).setOnClickListener { setPreset(0, 15) }
        findViewById<Button>(R.id.btnPreset30m).setOnClickListener { setPreset(0, 30) }
        findViewById<Button>(R.id.btnPreset1h).setOnClickListener { setPreset(1, 0) }
        findViewById<Button>(R.id.btnPreset2h).setOnClickListener { setPreset(2, 0) }

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnGrantDnd.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        btnGrantUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnGrantCall.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                100
            )
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionUI()
    }

    private fun setPreset(hours: Int, minutes: Int) {
        etHours.setText(hours.toString())
        etMinutes.setText(minutes.toString())
    }

    private fun onStartLockClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 [1. 화면 고정 권한]을 허용해주세요.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (!LockManager.isUsageAccessGranted(this)) {
            Toast.makeText(this, "전화 앱 이탈 방지를 위해 [3. 사용정보 접근 권한]을 허용해주세요.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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
                "• 화면 전체에 시계 잠금화면이 고정되어 홈 버튼이나 다른 앱으로 절대 나갈 수 없습니다.\n" +
                "• 전화는 잠금화면 내장 다이얼패드로만 걸 수 있으며 통화 화면에서 벗어나는 즉시 다시 잠깁니다.\n" +
                "• 즉시 무음(방해금지) 상태로 전환되며 알림 팝업이 차단됩니다.\n" +
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
        val usageGranted = LockManager.isUsageAccessGranted(this)
        val callGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        btnGrantOverlay.text = if (overlayGranted) "1. 화면 고정(다른 앱 위에 표시) 권한 [완료]" else "1. 화면 고정(다른 앱 위에 표시) 권한 허용하기"
        btnGrantDnd.text = if (dndGranted) "2. 방해금지(무음/알림차단) 권한 [완료]" else "2. 방해금지(무음/알림차단) 권한 허용하기"
        btnGrantUsage.text = if (usageGranted) "3. 전화 외 타 앱 이탈 감지(사용정보 접근) [완료]" else "3. 전화 외 타 앱 이탈 감지(사용정보 접근) 허용하기"
        btnGrantCall.text = if (callGranted) "4. 전화 바로 발신 권한 [완료]" else "4. 전화 바로 발신 권한 허용하기"

        val allGranted = overlayGranted && dndGranted && usageGranted && callGranted
        tvPermStatus.text = if (allGranted) {
            "모든 권한이 준비되었습니다. 완벽하게 잠글 수 있습니다."
        } else {
            "접근성(제한된 설정) 없이 아래 권한들을 켜주세요."
        }
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }
}
