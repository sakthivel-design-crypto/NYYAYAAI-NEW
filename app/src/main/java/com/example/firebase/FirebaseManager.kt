package com.example.firebase

import android.content.Context
import android.util.Log
import com.example.db.CitizenComplaint
import com.example.db.ForumPost
import com.example.db.IncidentReport
import com.example.db.UserAccount
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private var isFirebaseAvailable = false

    fun init(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            isFirebaseAvailable = true
            Log.d(TAG, "Firebase initialized successfully.")
        } catch (e: Exception) {
            isFirebaseAvailable = false
            Log.w(TAG, "Firebase initialization skipped or failed: ${e.message}")
        }
    }

    val auth: FirebaseAuth?
        get() = try {
            if (isFirebaseAvailable) FirebaseAuth.getInstance() else null
        } catch (e: Exception) {
            null
        }

    val firestore: FirebaseFirestore?
        get() = try {
            if (isFirebaseAvailable) FirebaseFirestore.getInstance() else null
        } catch (e: Exception) {
            null
        }

    val storage: com.google.firebase.storage.FirebaseStorage?
        get() = try {
            if (isFirebaseAvailable) com.google.firebase.storage.FirebaseStorage.getInstance() else null
        } catch (e: Exception) {
            null
        }

    fun getCurrentUser(): FirebaseUser? {
        return auth?.currentUser
    }

    // Image Upload to Firebase Storage
    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                val activeNetwork = cm.activeNetworkInfo
                activeNetwork != null && activeNetwork.isConnected
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun parseStorageException(e: Exception): String {
        if (e is com.google.firebase.storage.StorageException) {
            return when (e.errorCode) {
                com.google.firebase.storage.StorageException.ERROR_BUCKET_NOT_FOUND ->
                    "Invalid Storage Bucket: Firebase Storage bucket does not exist or is misconfigured."
                com.google.firebase.storage.StorageException.ERROR_PROJECT_NOT_FOUND ->
                    "Invalid Storage Bucket: Firebase project not found."
                com.google.firebase.storage.StorageException.ERROR_NOT_AUTHORIZED,
                com.google.firebase.storage.StorageException.ERROR_NOT_AUTHENTICATED ->
                    "Permission denied: Firebase Storage access denied by security rules."
                com.google.firebase.storage.StorageException.ERROR_QUOTA_EXCEEDED ->
                    "Storage quota exceeded: Storage space limit reached."
                com.google.firebase.storage.StorageException.ERROR_RETRY_LIMIT_EXCEEDED ->
                    "Upload timeout: Request timed out. Please check your network connection."
                com.google.firebase.storage.StorageException.ERROR_CANCELED ->
                    "Upload cancelled by user."
                else -> e.localizedMessage ?: "Storage operation failed (Code ${e.errorCode})"
            }
        }
        return e.localizedMessage ?: "Storage failed: An unknown error occurred."
    }

    // Image Upload to Firebase Storage with Compression, Diagnostics, Progress, Timeout & Automatic Retries
    fun uploadComplaintImage(
        context: Context,
        complaintId: String,
        imageUriStr: String?,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        if (imageUriStr.isNullOrEmpty()) {
            Log.d(TAG, "No image URI provided for complaint $complaintId, skipping upload")
            onComplete(true, null, null)
            return
        }

        if (imageUriStr.startsWith("http://") || imageUriStr.startsWith("https://")) {
            Log.d(TAG, "Image URI is already a remote download URL: $imageUriStr")
            onComplete(true, imageUriStr, null)
            return
        }

        Log.d(TAG, "Starting upload...")
        Log.d(TAG, "Image Selected: ${if (imageUriStr.startsWith("data:")) "Base64 Image Data" else imageUriStr}")

        val storageInstance = storage
        if (storageInstance == null) {
            val err = "Firebase Storage instance unavailable"
            Log.e(TAG, "Upload Failed (Full Exception): $err")
            onComplete(false, null, err)
            return
        }

        if (!isNetworkAvailable(context)) {
            val err = "No Internet Connection. Please connect to the internet and retry."
            Log.e(TAG, "Upload Failed (Full Exception): $err")
            onComplete(false, null, err)
            return
        }

        val tempDir = java.io.File(context.cacheDir, "images")
        if (!tempDir.exists()) tempDir.mkdirs()
        val tempFile = java.io.File(tempDir, "temp_upload_${System.currentTimeMillis()}.jpg")

        fun cleanupCache() {
            try {
                if (tempFile.exists()) tempFile.delete()
                if (imageUriStr.startsWith("file://")) {
                    val file = java.io.File(android.net.Uri.parse(imageUriStr).path ?: "")
                    if (file.exists() && file.parentFile?.absolutePath == context.cacheDir.absolutePath) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }

        val isBase64 = imageUriStr.startsWith("data:image") ||
                (!imageUriStr.startsWith("content://") && !imageUriStr.startsWith("file://") && imageUriStr.length > 200)

        try {
            if (isBase64) {
                val cleanBase64 = if (imageUriStr.contains(",")) imageUriStr.substringAfter(",") else imageUriStr
                val rawBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                java.io.FileOutputStream(tempFile).use { it.write(rawBytes) }
                Log.d(TAG, "Temporary file created")
            } else {
                val uri = android.net.Uri.parse(imageUriStr)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Temporary file created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare temporary file: ${e.message}", e)
            cleanupCache()
            onComplete(false, null, "Unable to process selected image file.")
            return
        }

        if (!tempFile.exists() || tempFile.length() == 0L) {
            cleanupCache()
            onComplete(false, null, "Unable to read image file for upload.")
            return
        }

        // STEP 2: Compression from temp file
        Log.d(TAG, "Compressing image...")
        val bytesToUpload: ByteArray = try {
            val originalBitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
            if (originalBitmap != null) {
                val maxDim = 1920
                val width = originalBitmap.width
                val height = originalBitmap.height
                val scaledBitmap = if (width > maxDim || height > maxDim) {
                    val scale = maxDim.toFloat() / Math.max(width, height)
                    val newW = (width * scale).toInt()
                    val newH = (height * scale).toInt()
                    android.graphics.Bitmap.createScaledBitmap(originalBitmap, newW, newH, true)
                } else {
                    originalBitmap
                }

                val baos = java.io.ByteArrayOutputStream()
                scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                val compressed = baos.toByteArray()
                if (scaledBitmap != originalBitmap) {
                    scaledBitmap.recycle()
                }
                originalBitmap.recycle()
                Log.d(TAG, "Compression completed")
                compressed
            } else {
                Log.w(TAG, "Unable to compress image. Using original image.")
                Log.d(TAG, "Compression completed")
                tempFile.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to compress image. Using original image.", e)
            Log.d(TAG, "Compression completed")
            try {
                tempFile.readBytes()
            } catch (_: Exception) {
                cleanupCache()
                onComplete(false, null, "Failed to read compressed image data.")
                return
            }
        }

        // STEP 3 & 16: Storage path requirement: complaint_images/{complaintId}/{timestamp}.jpg
        val timestamp = System.currentTimeMillis()
        val storagePath = "complaint_images/$complaintId/$timestamp.jpg"
        val storageRef = storageInstance.reference.child(storagePath)
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("uploadedBy", complaintId)
            .build()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        fun attemptUpload(attempt: Int) {
            Log.d(TAG, "Uploading...")
            Log.d(TAG, "Uploading to Firebase Storage...")

            var uploadTask: com.google.firebase.storage.UploadTask? = null
            try {
                uploadTask = storageRef.putBytes(bytesToUpload, metadata)
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Storage putBytes error: ${e.message}", e)
                cleanupCache()
                val diagMsg = parseStorageException(e)
                onComplete(false, null, diagMsg)
                return
            }

            var isFinished = false

            // 20 Second Timeout Handler (Requirement 14)
            val timeoutRunnable = Runnable {
                if (!isFinished) {
                    isFinished = true
                    Log.e(TAG, "Upload Failed (Full Exception): Upload timed out after 20 seconds")
                    try { uploadTask?.cancel() } catch (_: Exception) {}
                    cleanupCache()
                    onComplete(false, null, "Upload timed out after 20 seconds. Please check your internet connection and retry.")
                }
            }
            handler.postDelayed(timeoutRunnable, 20_000L)

            uploadTask.addOnProgressListener { snapshot ->
                if (!isFinished && snapshot.totalByteCount > 0) {
                    val pct = ((100.0 * snapshot.bytesTransferred) / snapshot.totalByteCount).toInt()
                    onProgress?.invoke(pct.coerceIn(0, 100))
                }
            }.addOnSuccessListener {
                if (!isFinished) {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    Log.d(TAG, "Upload completed.")
                    Log.d(TAG, "Upload success")
                    Log.d(TAG, "Getting download URL...")

                    storageRef.downloadUrl
                        .addOnSuccessListener { downloadUri ->
                            val downloadUrl = downloadUri.toString()
                            Log.d(TAG, "Download URL Generated: $downloadUrl")
                            cleanupCache()
                            onComplete(true, downloadUrl, null)
                        }
                        .addOnFailureListener { e ->
                            val diagMsg = parseStorageException(e)
                            Log.e(TAG, "Getting download URL failed - $diagMsg", e)
                            cleanupCache()
                            onComplete(false, null, diagMsg)
                        }
                }
            }.addOnFailureListener { e ->
                if (!isFinished) {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    val diagMsg = parseStorageException(e)
                    Log.e(TAG, "Upload Failed on attempt $attempt/3: $diagMsg", e)
                    if (attempt < 3 && isNetworkAvailable(context)) {
                        val backoffMs = attempt * 1500L
                        Log.d(TAG, "Retrying upload in ${backoffMs}ms (Attempt ${attempt + 1}/3)...")
                        handler.postDelayed({ attemptUpload(attempt + 1) }, backoffMs)
                    } else {
                        Log.e(TAG, "Upload Failed - exhausted retries: $diagMsg", e)
                        cleanupCache()
                        val sanitizedMsg = if (diagMsg.contains("data:image")) "Image upload failed" else diagMsg
                        onComplete(false, null, sanitizedMsg)
                    }
                }
            }
        }

        attemptUpload(1)
    }

    // Overloaded variant for backward compatibility
    fun uploadComplaintImage(
        complaintId: String,
        imageUriStr: String?,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        if (imageUriStr.isNullOrEmpty()) {
            onComplete(true, null, null)
            return
        }
        if (imageUriStr.startsWith("http://") || imageUriStr.startsWith("https://")) {
            onComplete(true, imageUriStr, null)
            return
        }
        val storageInstance = storage
        if (storageInstance == null) {
            onComplete(false, null, "Firebase Storage instance unavailable")
            return
        }
        try {
            val uri = android.net.Uri.parse(imageUriStr)
            val fileName = "complaint_${complaintId}_${System.currentTimeMillis()}.jpg"
            val storageRef = storageInstance.reference.child("complaint_images/$fileName")
            storageRef.putFile(uri)
                .addOnSuccessListener {
                    storageRef.downloadUrl
                        .addOnSuccessListener { downloadUri ->
                            onComplete(true, downloadUri.toString(), null)
                        }
                        .addOnFailureListener { e ->
                            onComplete(false, null, parseStorageException(e))
                        }
                }
                .addOnFailureListener { e ->
                    onComplete(false, null, parseStorageException(e))
                }
        } catch (e: Exception) {
            onComplete(false, null, e.localizedMessage ?: "Storage exception")
        }
    }

    // Auth methods
    suspend fun signInWithEmail(email: String, pass: String): Boolean {
        val authInstance = auth ?: return false
        return try {
            authInstance.signInWithEmailAndPassword(email, pass).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Email Sign In failed: ${e.message}")
            false
        }
    }

    suspend fun registerWithEmail(email: String, pass: String): Boolean {
        val authInstance = auth ?: return false
        return try {
            authInstance.createUserWithEmailAndPassword(email, pass).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Register failed: ${e.message}")
            false
        }
    }

    suspend fun signInWithGoogleToken(idToken: String): Boolean {
        val authInstance = auth ?: return false
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            authInstance.signInWithCredential(credential).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign In failed: ${e.message}")
            false
        }
    }

    fun signOut() {
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign out error: ${e.message}")
        }
    }

    // Firestore Complaints Sync - SINGLE SOURCE OF TRUTH (Collection: complaints)
    fun syncComplaint(complaint: CitizenComplaint, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val db = firestore ?: run {
            Log.w(TAG, "Firestore unavailable, proceeding with local DB fallback for ${complaint.id}")
            onComplete?.invoke(true, null)
            return
        }
        try {
            Log.d(TAG, "Writing Firestore for complaint ${complaint.id}")
            val complaintData = hashMapOf(
                "complaintId" to complaint.id,
                "id" to complaint.id,
                "citizenId" to complaint.citizenId,
                "citizenUid" to (auth?.currentUser?.uid ?: complaint.citizenId),
                "citizenName" to complaint.reporterName,
                "reporterName" to complaint.reporterName,
                "citizenEmail" to complaint.reporterEmail,
                "reporterEmail" to complaint.reporterEmail,
                "citizenPhone" to complaint.citizenPhone,
                "department" to complaint.department,
                "aiPredictedDepartment" to complaint.aiPredictedDepartment,
                "category" to complaint.category,
                "priority" to complaint.priority,
                "title" to complaint.title,
                "description" to complaint.description,
                "address" to complaint.address,
                "latitude" to complaint.latitude,
                "longitude" to complaint.longitude,
                "district" to complaint.district,
                "state" to complaint.state,
                "anonymous" to complaint.isAnonymous,
                "isAnonymous" to complaint.isAnonymous,
                "imageUrl" to (complaint.imageUri ?: ""),
                "imageUri" to (complaint.imageUri ?: ""),
                "photoUrl" to (complaint.imageUri ?: ""),
                "fileName" to complaint.photoFileName,
                "photoFileName" to complaint.photoFileName,
                "uploadTime" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "uploadedBy" to complaint.reporterName,
                "status" to complaint.status,
                "assignedOfficer" to complaint.assignedOfficer,
                "authorityNotes" to complaint.authorityRemarks,
                "authorityRemarks" to complaint.authorityRemarks,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "resolvedAt" to complaint.resolvedAt,
                "lastModifiedBy" to complaint.lastModifiedBy,
                "timeline" to complaint.timeline,
                "notificationHistory" to complaint.notificationHistory,
                "timestamp" to complaint.timestamp
            )
            db.collection("complaints").document(complaint.id)
                .set(complaintData)
                .addOnSuccessListener {
                    Log.d(TAG, "Firestore saved")
                    Log.d(TAG, "Firestore Saved: Complaint ${complaint.id} written successfully")
                    onComplete?.invoke(true, null)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firestore failed: ${e.message}")
                    onComplete?.invoke(false, e.localizedMessage ?: "Firestore save failed")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore sync complaint error: ${e.message}", e)
            onComplete?.invoke(false, e.localizedMessage)
        }
    }

    // Update Complaint Fields by Authority
    fun updateComplaintFieldsByAuthority(
        complaintId: String,
        status: String,
        department: String,
        assignedOfficer: String,
        authorityNotes: String,
        resolvedAt: Long,
        timelineJson: String,
        notificationHistoryJson: String,
        lastModifiedBy: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val db = firestore ?: run {
            Log.w(TAG, "Firestore unavailable for authority update")
            onComplete?.invoke(false, "Firestore unavailable")
            return
        }
        try {
            val docRef = db.collection("complaints").document(complaintId)
            val isResolved = status.equals("Resolved", ignoreCase = true) || status.equals("Closed", ignoreCase = true)

            val updateMap = hashMapOf<String, Any>(
                "status" to status,
                "department" to department,
                "aiPredictedDepartment" to department,
                "assignedOfficer" to assignedOfficer,
                "authorityNotes" to authorityNotes,
                "authorityRemarks" to authorityNotes,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "timeline" to timelineJson,
                "notificationHistory" to notificationHistoryJson,
                "lastModifiedBy" to lastModifiedBy
            )

            if (isResolved) {
                updateMap["resolvedAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
            }

            docRef.update(updateMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Complaint $complaintId successfully updated in Firestore with serverTimestamp")
                    onComplete?.invoke(true, null)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Update failed for $complaintId, falling back to set merge: ${e.message}")
                    updateMap["complaintId"] = complaintId
                    updateMap["id"] = complaintId
                    docRef.set(updateMap, com.google.firebase.firestore.SetOptions.merge())
                        .addOnSuccessListener {
                            Log.d(TAG, "Complaint $complaintId merged in Firestore")
                            onComplete?.invoke(true, null)
                        }
                        .addOnFailureListener { err ->
                            Log.e(TAG, "Failed to merge complaint $complaintId: ${err.message}")
                            onComplete?.invoke(false, err.localizedMessage)
                        }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during authority complaint update: ${e.message}", e)
            onComplete?.invoke(false, e.localizedMessage)
        }
    }

    private fun parseTimestampField(doc: com.google.firebase.firestore.DocumentSnapshot, field: String, defaultVal: Long = System.currentTimeMillis()): Long {
        val raw = doc.get(field)
        return when (raw) {
            is com.google.firebase.Timestamp -> raw.toDate().time
            is Long -> raw
            is Number -> raw.toLong()
            else -> defaultVal
        }
    }

    fun listenToComplaints(onUpdate: (List<CitizenComplaint>) -> Unit): ListenerRegistration? {
        val db = firestore ?: return null
        return try {
            db.collection("complaints")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val complaints = mutableListOf<CitizenComplaint>()
                    for (doc in snapshot.documents) {
                        try {
                            val id = doc.getString("complaintId") ?: doc.getString("id") ?: doc.id
                            val title = doc.getString("title") ?: ""
                            val description = doc.getString("description") ?: ""
                            val category = doc.getString("category") ?: "General"
                            val state = doc.getString("state") ?: ""
                            val district = doc.getString("district") ?: ""
                            val address = doc.getString("address") ?: ""
                            
                            val rawPhoto = doc.getString("photoUrl") ?: doc.getString("imageUri")
                            val photoUrl = rawPhoto.takeIf { !it.isNullOrEmpty() }
                            
                            val timestamp = parseTimestampField(doc, "timestamp", System.currentTimeMillis())
                            val isAnonymous = doc.getBoolean("anonymous") ?: doc.getBoolean("isAnonymous") ?: false
                            val citizenName = doc.getString("citizenName") ?: doc.getString("reporterName") ?: "Anonymous"
                            val citizenEmail = doc.getString("citizenEmail") ?: doc.getString("reporterEmail") ?: ""
                            val citizenPhone = doc.getString("citizenPhone") ?: "+91 98765 43210"
                            val status = doc.getString("status") ?: "Submitted"
                            val department = doc.getString("department") ?: doc.getString("aiPredictedDepartment") ?: category
                            val priority = doc.getString("priority") ?: "Medium"
                            val assignedOfficer = doc.getString("assignedOfficer") ?: ""
                            val authorityNotes = doc.getString("authorityNotes") ?: doc.getString("authorityRemarks") ?: ""
                            val lat = doc.getDouble("latitude") ?: 28.6139
                            val lng = doc.getDouble("longitude") ?: 77.2090
                            
                            val createdAt = parseTimestampField(doc, "createdAt", timestamp)
                            val updatedAt = parseTimestampField(doc, "updatedAt", timestamp)
                            val resolvedAt = parseTimestampField(doc, "resolvedAt", 0L)
                            val lastModifiedBy = doc.getString("lastModifiedBy") ?: "System"
                            val photoFileName = doc.getString("photoFileName") ?: "evidence.jpg"
                            val timeline = doc.getString("timeline") ?: "[]"
                            val notificationHistory = doc.getString("notificationHistory") ?: "[]"

                            complaints.add(
                                CitizenComplaint(
                                    id = id,
                                    title = title,
                                    description = description,
                                    category = category,
                                    state = state,
                                    district = district,
                                    address = address,
                                    imageUri = photoUrl,
                                    timestamp = timestamp,
                                    isAnonymous = isAnonymous,
                                    reporterName = citizenName,
                                    reporterEmail = citizenEmail,
                                    citizenPhone = citizenPhone,
                                    status = status,
                                    aiPredictedDepartment = department,
                                    priority = priority,
                                    assignedOfficer = assignedOfficer,
                                    authorityRemarks = authorityNotes,
                                    latitude = lat,
                                    longitude = lng,
                                    createdAt = createdAt,
                                    updatedAt = updatedAt,
                                    resolvedAt = resolvedAt,
                                    lastModifiedBy = lastModifiedBy,
                                    photoFileName = photoFileName,
                                    timeline = timeline,
                                    notificationHistory = notificationHistory
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing complaint document: ${e.message}")
                        }
                    }
                    if (complaints.isNotEmpty()) {
                        // Sort by createdAt DESC so newest complaint is always at top
                        onUpdate(complaints.sortedByDescending { it.createdAt.coerceAtLeast(it.timestamp) })
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up complaints listener: ${e.message}")
            null
        }
    }

    // Sync User Account
    fun syncUserAccount(account: UserAccount) {
        val db = firestore ?: return
        try {
            val userMap = hashMapOf(
                "email" to account.email,
                "name" to account.name,
                "role" to account.role,
                "department" to account.department,
                "district" to account.district,
                "contact" to account.contact,
                "performanceScore" to account.performanceScore,
                "isApproved" to account.isApproved
            )
            db.collection("users").document(account.email).set(userMap)
        } catch (e: Exception) {
            Log.e(TAG, "Sync user error: ${e.message}")
        }
    }

    // Sync Forum Post
    fun syncForumPost(post: ForumPost) {
        val db = firestore ?: return
        try {
            val postMap = hashMapOf(
                "id" to post.id,
                "title" to post.title,
                "content" to post.content,
                "postType" to post.postType,
                "authorName" to post.authorName,
                "authorRole" to post.authorRole,
                "upvotes" to post.upvotes,
                "timestamp" to post.timestamp
            )
            db.collection("forum_posts").document(post.id).set(postMap)
        } catch (e: Exception) {
            Log.e(TAG, "Sync forum post error: ${e.message}")
        }
    }

    // Sync Audit Log
    fun syncAuditLog(log: com.example.db.AuditLog) {
        val db = firestore ?: return
        try {
            val logMap = hashMapOf(
                "id" to log.id,
                "complaintId" to log.complaintId,
                "officerName" to log.officerName,
                "timestamp" to log.timestamp,
                "action" to log.action,
                "previousStatus" to log.previousStatus,
                "newStatus" to log.newStatus,
                "notes" to log.notes
            )
            db.collection("audit_logs").document(log.id).set(logMap)
        } catch (e: Exception) {
            Log.e(TAG, "Sync audit log error: ${e.message}")
        }
    }

    // Sync Notification
    fun syncNotification(notification: com.example.db.Notification) {
        val db = firestore ?: return
        try {
            val notifMap = hashMapOf(
                "id" to notification.id,
                "userEmail" to notification.userEmail,
                "title" to notification.title,
                "message" to notification.message,
                "timestamp" to notification.timestamp,
                "isRead" to notification.isRead,
                "isAuthority" to notification.isAuthority,
                "isHighPriority" to notification.isHighPriority
            )
            db.collection("notifications").document(notification.id).set(notifMap)
        } catch (e: Exception) {
            Log.e(TAG, "Sync notification error: ${e.message}")
        }
    }

    fun listenToNotifications(onUpdate: (List<com.example.db.Notification>) -> Unit): ListenerRegistration? {
        val db = firestore ?: return null
        return try {
            db.collection("notifications")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val notifications = mutableListOf<com.example.db.Notification>()
                    for (doc in snapshot.documents) {
                        try {
                            val id = doc.getString("id") ?: doc.id
                            val userEmail = doc.getString("userEmail") ?: "all"
                            val title = doc.getString("title") ?: ""
                            val message = doc.getString("message") ?: ""
                            val timestamp = parseTimestampField(doc, "timestamp", System.currentTimeMillis())
                            val isRead = doc.getBoolean("isRead") ?: false
                            val isAuthority = doc.getBoolean("isAuthority") ?: false
                            val isHighPriority = doc.getBoolean("isHighPriority") ?: false
                            notifications.add(
                                com.example.db.Notification(
                                    id = id,
                                    userEmail = userEmail,
                                    title = title,
                                    message = message,
                                    timestamp = timestamp,
                                    isRead = isRead,
                                    isAuthority = isAuthority,
                                    isHighPriority = isHighPriority
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing notification document: ${e.message}")
                        }
                    }
                    if (notifications.isNotEmpty()) {
                        onUpdate(notifications)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up notifications listener: ${e.message}")
            null
        }
    }

    // Sync Complaint Reply
    fun syncComplaintReply(reply: com.example.db.ComplaintReply) {
        val db = firestore ?: return
        try {
            val replyMap = hashMapOf(
                "id" to reply.id,
                "complaintId" to reply.complaintId,
                "authorityName" to reply.authorityName,
                "department" to reply.department,
                "timestamp" to reply.timestamp,
                "message" to reply.message,
                "updatedStatus" to reply.updatedStatus,
                "attachmentPath" to (reply.attachmentPath ?: "")
            )
            db.collection("complaint_replies").document(reply.id).set(replyMap)
        } catch (e: Exception) {
            Log.e(TAG, "Sync complaint reply error: ${e.message}")
        }
    }

    // Sync Citizen Inquiry Request
    fun syncCitizenRequest(request: com.example.db.CitizenRequest) {
        val db = firestore ?: return
        try {
            val reqMap = hashMapOf(
                "id" to request.id,
                "citizenEmail" to request.citizenEmail,
                "citizenName" to request.citizenName,
                "subject" to request.subject,
                "details" to request.details,
                "status" to request.status,
                "reply" to request.reply,
                "timestamp" to request.timestamp
            )
            db.collection("citizen_requests").document(request.id).set(reqMap)
        } catch (e: Exception) {
            Log.e(TAG, "Sync citizen request error: ${e.message}")
        }
    }

    fun listenToCitizenRequests(onUpdate: (List<com.example.db.CitizenRequest>) -> Unit): ListenerRegistration? {
        val db = firestore ?: return null
        return try {
            db.collection("citizen_requests")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val requests = mutableListOf<com.example.db.CitizenRequest>()
                    for (doc in snapshot.documents) {
                        try {
                            val id = doc.getString("id") ?: doc.id
                            val citizenEmail = doc.getString("citizenEmail") ?: ""
                            val citizenName = doc.getString("citizenName") ?: "Citizen"
                            val subject = doc.getString("subject") ?: ""
                            val details = doc.getString("details") ?: ""
                            val status = doc.getString("status") ?: "Open"
                            val reply = doc.getString("reply") ?: ""
                            val timestamp = parseTimestampField(doc, "timestamp", System.currentTimeMillis())
                            requests.add(
                                com.example.db.CitizenRequest(
                                    id = id,
                                    citizenName = citizenName,
                                    citizenEmail = citizenEmail,
                                    subject = subject,
                                    details = details,
                                    status = status,
                                    reply = reply,
                                    timestamp = timestamp
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing citizen request document: ${e.message}")
                        }
                    }
                    if (requests.isNotEmpty()) {
                        onUpdate(requests)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up citizen requests listener: ${e.message}")
            null
        }
    }

    fun listenToUsers(onUpdate: (List<UserAccount>) -> Unit): ListenerRegistration? {
        val db = firestore ?: return null
        return try {
            db.collection("users")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val users = mutableListOf<UserAccount>()
                    for (doc in snapshot.documents) {
                        try {
                            val email = doc.getString("email") ?: doc.id
                            val name = doc.getString("name") ?: ""
                            val role = doc.getString("role") ?: "Citizen"
                            val department = doc.getString("department") ?: ""
                            val district = doc.getString("district") ?: ""
                            val contact = doc.getString("contact") ?: ""
                            val performanceScore = (doc.getLong("performanceScore") ?: 100L).toInt()
                            val isApproved = doc.getBoolean("isApproved") ?: true
                            users.add(
                                UserAccount(
                                    email = email,
                                    passwordHash = "",
                                    name = name,
                                    role = role,
                                    department = department,
                                    district = district,
                                    contact = contact,
                                    performanceScore = performanceScore,
                                    isApproved = isApproved
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing user document: ${e.message}")
                        }
                    }
                    if (users.isNotEmpty()) {
                        onUpdate(users)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up users listener: ${e.message}")
            null
        }
    }

    fun sendComplaintMessage(complaintId: String, msg: com.example.db.ComplaintMessage, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val db = firestore ?: run {
            onComplete?.invoke(false, "Firestore unavailable")
            return
        }
        try {
            val messageData = hashMapOf(
                "messageId" to msg.messageId,
                "complaintId" to complaintId,
                "senderId" to msg.senderId,
                "senderRole" to msg.senderRole,
                "senderName" to msg.senderName,
                "message" to msg.message,
                "messageType" to msg.messageType,
                "createdAt" to FieldValue.serverTimestamp(),
                "editedAt" to msg.editedAt,
                "isRead" to msg.isRead,
                "deleted" to msg.deleted,
                "attachmentUrl" to (msg.attachmentUrl ?: "")
            )
            db.collection("complaints")
                .document(complaintId)
                .collection("messages")
                .document(msg.messageId)
                .set(messageData)
                .addOnSuccessListener {
                    onComplete?.invoke(true, null)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error sending complaint message to Firestore: ${e.message}")
                    onComplete?.invoke(false, e.localizedMessage)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendComplaintMessage: ${e.message}", e)
            onComplete?.invoke(false, e.localizedMessage)
        }
    }

    fun listenToComplaintMessages(complaintId: String, onUpdate: (List<com.example.db.ComplaintMessage>) -> Unit): ListenerRegistration? {
        val db = firestore ?: return null
        return try {
            db.collection("complaints")
                .document(complaintId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val list = mutableListOf<com.example.db.ComplaintMessage>()
                    for (doc in snapshot.documents) {
                        try {
                            val messageId = doc.getString("messageId") ?: doc.id
                            val senderId = doc.getString("senderId") ?: ""
                            val senderRole = doc.getString("senderRole") ?: "CITIZEN"
                            val senderName = doc.getString("senderName") ?: ""
                            val message = doc.getString("message") ?: ""
                            val messageType = doc.getString("messageType") ?: "TEXT"
                            val createdAt = parseTimestampField(doc, "createdAt", System.currentTimeMillis())
                            val editedAt = doc.getLong("editedAt") ?: 0L
                            val isRead = doc.getBoolean("isRead") ?: false
                            val deleted = doc.getBoolean("deleted") ?: false
                            val attachmentUrl = doc.getString("attachmentUrl")

                            list.add(
                                com.example.db.ComplaintMessage(
                                    messageId = messageId,
                                    complaintId = complaintId,
                                    senderId = senderId,
                                    senderRole = senderRole,
                                    senderName = senderName,
                                    message = message,
                                    messageType = messageType,
                                    createdAt = createdAt,
                                    editedAt = editedAt,
                                    isRead = isRead,
                                    deleted = deleted,
                                    attachmentUrl = attachmentUrl
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing complaint message doc: ${e.message}")
                        }
                    }
                    onUpdate(list)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up complaint messages listener: ${e.message}")
            null
        }
    }

    fun markMessagesAsReadInFirestore(complaintId: String, senderRoleToMarkRead: String) {
        val db = firestore ?: return
        try {
            db.collection("complaints")
                .document(complaintId)
                .collection("messages")
                .whereEqualTo("senderRole", senderRoleToMarkRead)
                .whereEqualTo("isRead", false)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot != null && !snapshot.isEmpty) {
                        val batch = db.batch()
                        for (doc in snapshot.documents) {
                            batch.update(doc.reference, "isRead", true)
                        }
                        batch.commit()
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error marking messages as read in Firestore: ${e.message}")
        }
    }
}

