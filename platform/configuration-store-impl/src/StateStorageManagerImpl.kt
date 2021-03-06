/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.configurationStore

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.components.StateStorage.SaveSession
import com.intellij.openapi.components.StateStorageChooserEx.Resolution
import com.intellij.openapi.components.impl.stores.StateStorageManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.PathUtilRt
import com.intellij.util.ReflectionUtil
import com.intellij.util.SmartList
import com.intellij.util.ThreeState
import com.intellij.util.containers.ContainerUtil
import gnu.trove.THashMap
import org.jdom.Element
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.concurrent.withLock

/**
 * If componentManager not specified, storage will not add file tracker (see VirtualFileTracker)
 */
open class StateStorageManagerImpl(private val rootTagName: String,
                                   private val pathMacroSubstitutor: TrackingPathMacroSubstitutor? = null,
                                   val componentManager: ComponentManager? = null,
                                   private val virtualFileTracker: StorageVirtualFileTracker? = StateStorageManagerImpl.createDefaultVirtualTracker(componentManager) ) : StateStorageManager {
  private val macros: MutableList<Macro> = ContainerUtil.createLockFreeCopyOnWriteList()
  private val storageLock = ReentrantLock()
  private val storages = THashMap<String, StateStorage>()

  var streamProvider: StreamProvider? = null

  // access under storageLock
  private var isUseVfsListener = if (componentManager == null) ThreeState.NO else ThreeState.UNSURE // unsure because depends on stream provider state

  protected open val isUseXmlProlog: Boolean
    get() = true

  companion object {
    private val MACRO_PATTERN = Pattern.compile("(\\$[^\\$]*\\$)")

    fun createDefaultVirtualTracker(componentManager: ComponentManager?) = when (componentManager) {
      null -> {
        null
      }
      is Application -> {
        StorageVirtualFileTracker(componentManager.messageBus)
      }
      else -> {
        val tracker = (ApplicationManager.getApplication().stateStore.stateStorageManager as? StateStorageManagerImpl)?.virtualFileTracker
        if (tracker != null) {
          Disposer.register(componentManager, Disposable {
            tracker.remove { it.storageManager.componentManager == componentManager }
          })
        }
        tracker
      }
    }
  }

  override final fun getMacroSubstitutor() = pathMacroSubstitutor

  private data class Macro(val key: String, var value: String)

  @TestOnly fun getVirtualFileTracker() = virtualFileTracker

  /**
   * @param expansion System-independent.
   */
  fun addMacro(key: String, expansion: String):Boolean {
    assert(!key.isEmpty())

    val value: String
    if (expansion.contains("\\")) {
      val message = "Macro $key set to system-dependent expansion $expansion"
      if (ApplicationManager.getApplication().isUnitTestMode) {
        throw IllegalArgumentException(message)
      }
      else {
        LOG.warn(message)
        value = FileUtilRt.toSystemIndependentName(expansion)
      }
    }
    else {
      value = expansion
    }

    // you must not add duplicated macro, but our ModuleImpl.setModuleFilePath does it (it will be fixed later)
    for (macro in macros) {
      if (key.equals(macro.key)) {
        macro.value = value
        return false
      }
    }

    macros.add(Macro(key, value))
    return true
  }

  // system-independent paths
  open fun pathRenamed(oldPath: String, newPath: String, event: VFileEvent?) {
    for (macro in macros) {
      if (oldPath.equals(macro.value)) {
        macro.value = newPath
      }
    }
  }

  override final fun getStateStorage(storageSpec: Storage) = getOrCreateStorage(storageSpec.file, storageSpec.roamingType,
          JavaAnnotationHelperForKotlin.getStorageClass(storageSpec), JavaAnnotationHelperForKotlin.getStateSplitterClass(storageSpec))

  protected open fun normalizeFileSpec(fileSpec: String): String {
    val path = FileUtilRt.toSystemIndependentName(fileSpec)
    // fileSpec for directory based storage could be erroneously specified as "name/"
    return if (path.endsWith('/')) path.substring(0, path.length - 1) else path
  }

  fun getOrCreateStorage(collapsedPath: String,
                         roamingType: RoamingType = RoamingType.DEFAULT,
                         storageClass: Class<out StateStorage> = StateStorage::class.java,
                         @Suppress("DEPRECATION") @SuppressWarnings("deprecation") stateSplitter: Class<out StateSplitter> = StateSplitterEx::class.java): StateStorage {
    val normalizedCollapsedPath = normalizeFileSpec(collapsedPath)
    val key = if (storageClass == StateStorage::class.java) normalizedCollapsedPath else storageClass.name
    storageLock.withLock {
      var storage = storages.get(key)
      if (storage == null) {
        storage = createStateStorage(storageClass, normalizedCollapsedPath, roamingType, stateSplitter)
        storages.put(key, storage)
      }
      return storage
    }
  }

  fun getCachedFileStorages(changed: Collection<String>, deleted: Collection<String>) = storageLock.withLock { Pair(getCachedFileStorages(changed), getCachedFileStorages(deleted)) }

  fun getCachedFileStorages(fileSpecs: Collection<String>): Collection<FileBasedStorage> {
    if (fileSpecs.isEmpty()) {
      return emptyList()
    }

    storageLock.withLock {
      var result: MutableList<FileBasedStorage>? = null
      for (fileSpec in fileSpecs) {
        val storage = storages.get(normalizeFileSpec(fileSpec))
        if (storage is FileBasedStorage) {
          if (result == null) {
            result = SmartList<FileBasedStorage>()
          }
          result.add(storage)
        }
      }
      return result ?: emptyList()
    }
  }

  // overridden in upsource
  protected open fun createStateStorage(storageClass: Class<out StateStorage>,
                                        collapsedPath: String,
                                        roamingType: RoamingType,
                                        @Suppress("DEPRECATION") stateSplitter: Class<out StateSplitter>): StateStorage {
    if (storageClass != StateStorage::class.java) {
      val constructor = storageClass.constructors[0]!!
      constructor.isAccessible = true
      return constructor.newInstance(componentManager!!, this) as StateStorage
    }

    if (isUseVfsListener == ThreeState.UNSURE) {
      isUseVfsListener = ThreeState.fromBoolean(streamProvider == null || !streamProvider!!.enabled)
    }

    val filePath = expandMacros(collapsedPath)
    @Suppress("DEPRECATION")
    if (stateSplitter != StateSplitter::class.java && stateSplitter != StateSplitterEx::class.java) {
      val storage = MyDirectoryStorage(this, File(filePath), ReflectionUtil.newInstance(stateSplitter))
      virtualFileTracker?.put(filePath, storage)
      return storage
    }

    if (!ApplicationManager.getApplication().isHeadlessEnvironment && PathUtilRt.getFileName(filePath).lastIndexOf('.') < 0) {
      throw IllegalArgumentException("Extension is missing for storage file: $filePath")
    }

    val effectiveRoamingType = if (roamingType == RoamingType.DEFAULT && collapsedPath == StoragePathMacros.WORKSPACE_FILE) RoamingType.DISABLED else roamingType
    val storage = MyFileStorage(this, File(filePath), collapsedPath, rootTagName, effectiveRoamingType, getMacroSubstitutor(collapsedPath), streamProvider)
    if (isUseVfsListener == ThreeState.YES) {
      virtualFileTracker?.put(filePath, storage)
    }
    return storage
  }

  private class MyDirectoryStorage(override val storageManager: StateStorageManagerImpl, file: File, @Suppress("DEPRECATION") splitter: StateSplitter) :
    DirectoryBasedStorage(file, splitter, storageManager.pathMacroSubstitutor), StorageVirtualFileTracker.TrackedStorage

  private class MyFileStorage(override val storageManager: StateStorageManagerImpl,
                              file: File,
                              fileSpec: String,
                              rootElementName: String,
                              roamingType: RoamingType,
                              pathMacroManager: TrackingPathMacroSubstitutor? = null,
                              provider: StreamProvider? = null) : FileBasedStorage(file, fileSpec, rootElementName, pathMacroManager, roamingType, provider), StorageVirtualFileTracker.TrackedStorage {
    override val isUseXmlProlog: Boolean
      get() = storageManager.isUseXmlProlog

    override fun beforeElementSaved(element: Element) {
      storageManager.beforeElementSaved(element)
      super.beforeElementSaved(element)
    }

    override fun beforeElementLoaded(element: Element) {
      storageManager.beforeElementLoaded(element)
      super.beforeElementLoaded(element)
    }

    override fun dataLoadedFromProvider(element: Element?) {
      storageManager.dataLoadedFromProvider(this, element)
    }
  }

  protected open fun beforeElementSaved(element: Element) {
  }

  protected open fun beforeElementLoaded(element: Element) {
  }

  protected open fun dataLoadedFromProvider(storage: FileBasedStorage, element: Element?) {
  }

  override final fun rename(path: String, newName: String) {
    storageLock.withLock {
      val storage = getOrCreateStorage(collapseMacros(path), RoamingType.DEFAULT) as FileBasedStorage

      val file = storage.getVirtualFile()
      try {
        if (file != null) {
          file.rename(storage, newName)
        }
        else if (storage.file.name != newName) {
          // old file didn't exist or renaming failed
          val expandedPath = expandMacros(path)
          val parentPath = PathUtilRt.getParentPath(expandedPath)
          storage.setFile(null, File(parentPath, newName))
          pathRenamed(expandedPath, "$parentPath/$newName", null)
        }
      }
      catch (e: IOException) {
        LOG.debug(e)
      }
    }
  }

  fun clearStorages() {
    storageLock.withLock {
      try {
        if (virtualFileTracker != null) {
          storages.forEachEntry({ collapsedPath, storage ->
            virtualFileTracker.remove(expandMacros(collapsedPath))
            true
          })
        }
      }
      finally {
        storages.clear()
      }
    }
  }

  protected open fun getMacroSubstitutor(fileSpec: String): TrackingPathMacroSubstitutor? = pathMacroSubstitutor

  override fun expandMacros(path: String): String {
    // replacement can contains $ (php tests), so, this check must be performed before expand
    val matcher = MACRO_PATTERN.matcher(path)
    matcherLoop@
    while (matcher.find()) {
      val m = matcher.group(1)
      for (macro in macros) {
        if (macro.key == m) {
          continue@matcherLoop
        }
      }
      throw IllegalArgumentException("Unknown macro: $m in storage file spec: $path")
    }

    var expanded = path
    for ((key, value) in macros) {
      expanded = StringUtil.replace(expanded, key, value)
    }
    return expanded
  }

  fun expandMacro(macro: String): String {
    for ((key, value) in macros) {
      if (key == macro) {
        return value
      }
    }

    throw IllegalArgumentException("Unknown macro $macro")
  }

  fun collapseMacros(path: String): String {
    var result = path
    for ((key, value) in macros) {
      result = StringUtil.replace(result, value, key)
    }
    return normalizeFileSpec(result)
  }

  override final fun startExternalization() = object : StateStorageManager.ExternalizationSession {
    private val sessions = LinkedHashMap<StateStorage, StateStorage.ExternalizationSession>()

    override fun setState(storageSpecs: Array<Storage>, component: Any, componentName: String, state: Any) {
      val stateStorageChooser = component as? StateStorageChooserEx
      for (storageSpec in storageSpecs) {
        val resolution = if (stateStorageChooser == null) Resolution.DO else stateStorageChooser.getResolution(storageSpec, StateStorageOperation.WRITE)
        if (resolution == Resolution.SKIP) {
          continue
        }

        getExternalizationSession(getStateStorage(storageSpec))?.setState(component, componentName, if (storageSpec.deprecated || resolution == Resolution.CLEAR) Element("empty") else state)
      }
    }

    override fun setStateInOldStorage(component: Any, componentName: String, state: Any) {
      getOldStorage(component, componentName, StateStorageOperation.WRITE)?.let {
        getExternalizationSession(it)?.setState(component, componentName, state)
      }
    }

    private fun getExternalizationSession(storage: StateStorage): StateStorage.ExternalizationSession? {
      var session = sessions.get(storage)
      if (session == null) {
        session = storage.startExternalization()
        if (session != null) {
          sessions.put(storage, session)
        }
      }
      return session
    }

    override fun createSaveSessions(): List<SaveSession> {
      if (sessions.isEmpty()) {
        return emptyList()
      }

      var saveSessions: MutableList<SaveSession>? = null
      val externalizationSessions = sessions.values
      for (session in externalizationSessions) {
        val saveSession = session.createSaveSession()
        if (saveSession != null) {
          if (saveSessions == null) {
            if (externalizationSessions.size == 1) {
              return listOf(saveSession)
            }
            saveSessions = SmartList<SaveSession>()
          }
          saveSessions.add(saveSession)
        }
      }
      return ContainerUtil.notNullize(saveSessions)
    }
  }

  override final fun getOldStorage(component: Any, componentName: String, operation: StateStorageOperation): StateStorage? {
    val oldStorageSpec = getOldStorageSpec(component, componentName, operation) ?: return null
    @Suppress("DEPRECATION")
    return getOrCreateStorage(oldStorageSpec, if (component is com.intellij.openapi.util.RoamingTypeDisabled) RoamingType.DISABLED else RoamingType.DEFAULT)
  }

  protected open fun getOldStorageSpec(component: Any, componentName: String, operation: StateStorageOperation): String? = null
}

private fun String.startsWithMacro(macro: String): Boolean {
  val i = macro.length
  return length > i && this[i] == '/' && startsWith(macro)
}

fun removeMacroIfStartsWith(path: String, macro: String) = if (path.startsWithMacro(macro)) path.substring(macro.length + 1) else path