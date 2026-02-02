package com.sj.gpsutil.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class VehicleProfileRepository(private val context: Context) {
    
    suspend fun listProfiles(folderUri: String?): List<VehicleProfile> = withContext(Dispatchers.IO) {
        val profiles = mutableListOf<VehicleProfile>()
        
        if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            folder?.listFiles()?.forEach { file ->
                if (file.name?.endsWith(".profile.json") == true) {
                    val profile = loadProfileFromUri(file.uri)
                    if (profile != null) {
                        profiles.add(profile)
                    }
                }
            }
        } else {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME
            )
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%.profile.json")
            
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentValues().apply {
                        put(MediaStore.Downloads._ID, id)
                    }
                    val fileUri = Uri.withAppendedPath(collection, id.toString())
                    val profile = loadProfileFromUri(fileUri)
                    if (profile != null) {
                        profiles.add(profile)
                    }
                }
            }
        }
        
        profiles.sortedBy { it.name }
    }
    
    suspend fun saveProfile(profile: VehicleProfile, folderUri: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = "${profile.name}.profile.json"
            val json = profile.toJson()
            
            if (folderUri != null) {
                saveToFolder(folderUri, filename, json)
            } else {
                saveToDownloads(filename, json)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun loadProfile(profileName: String, folderUri: String?): VehicleProfile? = withContext(Dispatchers.IO) {
        val filename = "$profileName.profile.json"
        
        if (folderUri != null) {
            loadFromFolder(folderUri, filename)
        } else {
            loadFromDownloads(filename)
        }
    }
    
    suspend fun deleteProfile(profileName: String, folderUri: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = "$profileName.profile.json"
            
            if (folderUri != null) {
                deleteFromFolder(folderUri, filename)
            } else {
                deleteFromDownloads(filename)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun createDefaultProfiles(folderUri: String?) = withContext(Dispatchers.IO) {
        VehicleProfile.getDefaultProfiles().forEach { profile ->
            saveProfile(profile, folderUri)
        }
    }
    
    private fun saveToFolder(folderUri: String, filename: String, content: String) {
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: throw IllegalStateException("Invalid folder Uri")
        
        var file = folder.findFile(filename)
        if (file == null) {
            file = folder.createFile("application/json", filename)
                ?: throw IllegalStateException("Unable to create file in folder")
        }
        
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { outputStream ->
            OutputStreamWriter(outputStream).use { writer ->
                writer.write(content)
            }
        }
    }
    
    private fun saveToDownloads(filename: String, content: String) {
        val resolver = context.contentResolver
        
        val existingUri = findFileInDownloads(filename)
        if (existingUri != null) {
            resolver.openOutputStream(existingUri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                }
            }
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create file in Downloads")
            
            resolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                }
            }
        }
    }
    
    private fun loadFromFolder(folderUri: String, filename: String): VehicleProfile? {
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return null
        val file = folder.findFile(filename) ?: return null
        return loadProfileFromUri(file.uri)
    }
    
    private fun loadFromDownloads(filename: String): VehicleProfile? {
        val uri = findFileInDownloads(filename) ?: return null
        return loadProfileFromUri(uri)
    }
    
    private fun loadProfileFromUri(uri: Uri): VehicleProfile? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val json = reader.readText()
                    VehicleProfile.fromJson(json)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun deleteFromFolder(folderUri: String, filename: String) {
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return
        val file = folder.findFile(filename)
        file?.delete()
    }
    
    private fun deleteFromDownloads(filename: String) {
        val uri = findFileInDownloads(filename) ?: return
        context.contentResolver.delete(uri, null, null)
    }
    
    private fun findFileInDownloads(filename: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(filename)
        
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val id = cursor.getLong(idColumn)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
