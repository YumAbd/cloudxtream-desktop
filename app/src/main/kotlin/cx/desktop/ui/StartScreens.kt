package cx.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cx.desktop.core.Activation
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(painterResource("icon.png"), null, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
            Text("CloudXtream", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            content()
        }
    }
}

@Composable
fun LoadingScreen(text: String, fraction: Float?) = Centered {
    if (fraction == null) CircularProgressIndicator() else LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
    Text(text, color = Palette.muted, textAlign = TextAlign.Center)
}

@Composable
fun BlockedScreen(message: String, retry: () -> Unit) = Centered {
    Text(message, color = Palette.text, textAlign = TextAlign.Center)
    Button(onClick = retry) { Text("Cuba lagi") }
}

@Composable
fun ActivationScreen(initialMessage: String?, onAuthorized: (Activation.Result.Authorized) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(initialMessage) }
    var replaceChoices by remember { mutableStateOf<List<Activation.Device>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun activate(replace: String? = null) {
        if (busy || pin.length != 6) return
        busy = true
        message = null
        scope.launch {
            when (val result = Activation.activate(pin, replace)) {
                is Activation.Result.Authorized -> onAuthorized(result)
                is Activation.Result.Refused -> {
                    message = result.message
                    if (result.code == "device_limit_reached" && result.devices.isNotEmpty()) replaceChoices = result.devices
                }
            }
            busy = false
        }
    }

    Centered {
        Text("Masukkan PIN pengaktifan daripada admin CloudXtream.", color = Palette.muted, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = pin,
            onValueChange = { value -> pin = value.filter { it.isLetterOrDigit() }.uppercase().take(6) },
            singleLine = true,
            label = { Text("PIN (6 aksara)") },
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { activate() }),
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, letterSpacing = 6.sp, color = Palette.text),
        )
        Button(onClick = { activate() }, enabled = !busy && pin.length == 6, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Aktifkan")
        }
        message?.let { Text(it, color = Palette.error, textAlign = TextAlign.Center) }
    }

    if (replaceChoices.isNotEmpty()) {
        val format = SimpleDateFormat("d MMM yyyy, HH:mm")
        AlertDialog(
            onDismissRequest = { replaceChoices = emptyList() },
            title = { Text("Had peranti penuh") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pilih peranti lama yang akan digantikan dengan PC ini. Peranti itu tidak lagi boleh digunakan.", color = Palette.muted)
                    Spacer(Modifier.height(4.dp))
                    replaceChoices.forEach { device ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh)
                                .clickable { replaceChoices = emptyList(); activate(device.id) }.padding(12.dp)
                        ) {
                            Text(device.label, color = Palette.text, fontWeight = FontWeight.SemiBold)
                            if (device.lastSeenMs > 0) Text("Terakhir digunakan: " + format.format(Date(device.lastSeenMs)), color = Palette.muted, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { replaceChoices = emptyList() }) { Text("Batal") } },
        )
    }
}
