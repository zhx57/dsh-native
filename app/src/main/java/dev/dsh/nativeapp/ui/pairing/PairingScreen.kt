package dev.dsh.nativeapp.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.nativeapp.ui.theme.DshRadius
import dev.dsh.nativeapp.ui.theme.DshSpacing
import dev.dsh.nativeapp.ui.theme.LocalDshColors

/** First-run pairing: paste the URL that `dsh web` printed and connect. */
@Composable
fun PairingScreen(
    busy: Boolean,
    error: String?,
    onPair: (String) -> Unit,
) {
    val colors = LocalDshColors.current
    var input by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.xl.dp, vertical = DshSpacing.xxl.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(40.dp))

        Text(
            text = "连接 DSH",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.labelPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在电脑上运行 dsh web，把它打印的完整地址粘到这里。地址里带着一次性配对密钥。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = colors.labelTertiary,
        )

        Spacer(Modifier.height(DshSpacing.xxl.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.lg.dp))
                .background(colors.inputMajor, RoundedCornerShape(DshRadius.lg.dp))
                .padding(horizontal = DshSpacing.md.dp, vertical = DshSpacing.md.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = colors.labelPrimary,
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                text = "http://192.168.1.10:3080/?token=…",
                                fontSize = 13.sp,
                                color = colors.labelCaption,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        if (error != null) {
            Spacer(Modifier.height(DshSpacing.md.dp))
            Text(text = error, fontSize = 12.sp, lineHeight = 18.sp, color = colors.stateError)
        }

        Spacer(Modifier.height(DshSpacing.xl.dp))

        Button(
            onClick = { onPair(input) },
            enabled = !busy && input.isNotBlank(),
            shape = RoundedCornerShape(DshRadius.full.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.buttonPrimaryFill,
                contentColor = colors.buttonPrimaryForeground,
                disabledContainerColor = colors.bgSkeleton,
                disabledContentColor = colors.labelCaption,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.buttonPrimaryForeground,
                )
            } else {
                Text(text = "连接", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(DshSpacing.lg.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Hint("同一个局域网：用电脑的局域网 IP，不要在手机上填 127.0.0.1。")
            Hint("远程访问：先让 dsh web 监听可访问地址，再填对应地址。")
            Hint("配对密钥有时效，过期后重新生成即可。")
        }
    }
}

@Composable
private fun Hint(text: String) {
    val colors = LocalDshColors.current
    Text(text = "· $text", fontSize = 12.sp, lineHeight = 18.sp, color = colors.labelCaption)
}
