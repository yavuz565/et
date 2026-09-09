package com.mycompany.swipeclean20

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A real gallery-cleaning app.
 *
 * - Reads actual photos from the device via MediaStore (not a fake/demo list).
 * - Lets the user swipe left to delete, right to keep.
 * - Deletion is REAL: it goes through Android's scoped-storage delete APIs
 *   (MediaStore.createDeleteRequest on Android 11+, RecoverableSecurityException
 *   handling on Android 10, direct ContentResolver.delete on Android 9 and below),
 *   so the photo is actually removed from the device's gallery / MediaStore.
 */
data class Photo(val id: Long, val uri: Uri)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SwipeCleanApp()
                }
            }
        }
    }
}

private fun readMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

/** Queries the real device gallery for image entries, newest first. */
private fun loadPhotosFromGallery(context: android.content.Context): List<Photo> {
    val photos = mutableListOf<Photo>()
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            photos.add(Photo(id, uri))
        }
    }
    return photos
}

@Composable
fun SwipeCleanApp() {
    val context = androidx.compose.ui.platform.LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readMediaPermission()) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var deletedCount by remember { mutableIntStateOf(0) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }

    // Real permission request
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) photos = loadPhotosFromGallery(context)
    }

    // Handles the system "confirm delete" dialog for scoped storage (Android 10/11+)
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingDeleteUri?.let { uri ->
                photos = photos.filterNot { it.uri == uri }
                deletedCount++
            }
        }
        pendingDeleteUri = null
    }

    LaunchedEffect(Unit) {
        if (hasPermission) {
            photos = loadPhotosFromGallery(context)
        } else {
            permissionLauncher.launch(readMediaPermission())
        }
    }

    /** Actually deletes the photo from the device gallery via MediaStore. */
    fun deletePhotoForReal(photo: Photo) {
        try {
            val rows = context.contentResolver.delete(photo.uri, null, null)
            if (rows > 0) {
                photos = photos.filterNot { it.id == photo.id }
                deletedCount++
            }
        } catch (securityException: SecurityException) {
            // Scoped storage: the OS must show a confirmation dialog before deleting.
            val intentSender = when {
                Build.VERSION.SDK_INT >= 30 -> {
                    MediaStore.createDeleteRequest(
                        context.contentResolver,
                        listOf(photo.uri)
                    ).intentSender
                }
                Build.VERSION.SDK_INT == 29 -> {
                    (securityException as? RecoverableSecurityException)
                        ?.userAction?.actionIntent?.intentSender
                }
                else -> null
            }
            if (intentSender != null) {
                pendingDeleteUri = photo.uri
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SwipeClean 2.0",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        when {
            !hasPermission -> {
                Spacer(Modifier.weight(1f))
                Text("Fotoğraflarına erişim izni gerekiyor.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(readMediaPermission()) }) {
                    Text("İzin ver")
                }
                Spacer(Modifier.weight(1f))
            }
            photos.isEmpty() -> {
                Spacer(Modifier.weight(1f))
                Text("Galeride temizlenecek fotoğraf kalmadı 🎉")
                Text("Silinen: $deletedCount", modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.weight(1f))
            }
            else -> {
                Text("Kalan: ${photos.size}   •   Silinen: $deletedCount")
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val current = photos.first()
                    SwipeablePhotoCard(
                        key = current.id,
                        painter = rememberAsyncImagePainter(current.uri),
                        onSwipedLeft = { deletePhotoForReal(current) },
                        onSwipedRight = {
                            // Keep: just move it to the back of the queue
                            photos = photos.drop(1) + current
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Button(
                        onClick = { deletePhotoForReal(photos.first()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) { Text("Sil") }
                    Button(onClick = { photos = photos.drop(1) + photos.first() }) {
                        Text("Tut")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Sola kaydır: Sil   •   Sağa kaydır: Tut",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun SwipeablePhotoCard(
    key: Long,
    painter: Painter,
    onSwipedLeft: () -> Unit,
    onSwipedRight: () -> Unit
) {
    var offsetX by remember(key) { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offsetX"
    )
    val swipeThresholdPx = 350f

    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .fillMaxHeight(0.75f)
            .offset { androidx.compose.ui.unit.IntOffset(animatedOffsetX.roundToInt(), 0) }
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFEFEFEF))
            .pointerInput(key) {
                detectDragGestures(
                    onDragEnd = {
                        when {
                            offsetX < -swipeThresholdPx -> onSwipedLeft()
                            offsetX > swipeThresholdPx -> onSwipedRight()
                            else -> offsetX = 0f
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                }
            }
    ) {
        Image(
            painter = painter,
            contentDescription = "Galeri fotoğrafı",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (abs(offsetX) > 40f) {
            val isDelete = offsetX < 0
            Box(
                modifier = Modifier
                    .align(if (isDelete) Alignment.TopEnd else Alignment.TopStart)
                    .padding(16.dp)
                    .background(
                        if (isDelete) Color(0xCCE53935) else Color(0xCC43A047),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isDelete) "SİL" else "TUT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
