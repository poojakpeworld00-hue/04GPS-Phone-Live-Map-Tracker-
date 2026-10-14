package com.livemap.homedeck.ports

import androidx.room.*
import com.livemap.homedeck.entities.MainDisplayLatticeElement

@Dao
interface MainDisplayLatticeElementsDao {
    @Query("SELECT * FROM home_screen_grid_items")
    fun getAllItems(): List<MainDisplayLatticeElement>

    @Query("SELECT * FROM home_screen_grid_items WHERE parent_id = :folderId")
    fun getFolderItems(folderId: Long): List<MainDisplayLatticeElement>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: MainDisplayLatticeElement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<MainDisplayLatticeElement>)

    @Query("UPDATE home_screen_grid_items SET title = :title WHERE package_name = :packageName")
    fun reviseAppTitle(title: String, packageName: String)

    @Query("UPDATE home_screen_grid_items SET title = :title WHERE id = :id")
    fun reviseItemTitle(title: String, id: Long): Int

    @Query("UPDATE home_screen_grid_items SET `left` = :left, `top` = :top, `right` = :right, `bottom` = :bottom, `page` = :page, `docked` = :docked , `parent_id` = :parentId WHERE id = :id")
    fun reviseItemPosition(left: Int, top: Int, right: Int, bottom: Int, page: Int, docked: Boolean, parentId: Long?, id: Long)

    @Query("UPDATE home_screen_grid_items SET widget_id = :widgetId WHERE id = :id")
    fun reviseWidgetId(widgetId: Int, id: Long): Int

    @Query("DELETE FROM home_screen_grid_items WHERE id = :id")
    fun eraseItemById(id: Long)

    @Query("DELETE FROM home_screen_grid_items WHERE parent_id = :id")
    fun eraseItemsWithParentId(id: Long)

    @Transaction
    fun eraseById(id: Long) {
        eraseItemById(id)
        eraseItemsWithParentId(id)
    }

    /** Pseudo widgets are identified by their class name — see PSEUDO_WIDGET_* in Constants. */
    @Query("DELETE FROM home_screen_grid_items WHERE class_name = :className")
    fun eraseItemsByClassName(className: String)

    @Query("DELETE FROM home_screen_grid_items WHERE package_name = :packageName")
    fun eraseItemByPackageName(packageName: String)

    @Query("DELETE FROM home_screen_grid_items WHERE parent_id IN (SELECT id FROM home_screen_grid_items WHERE package_name = :packageName)")
    fun eraseItemsByParentPackageName(packageName: String)

    @Query("UPDATE home_screen_grid_items SET `left` = `left` + :shiftBy WHERE parent_id == :folderId AND `left` > :shiftFrom AND id != :excludingId")
    fun shiftFolderItems(folderId: Long, shiftFrom: Int, shiftBy: Int, excludingId: Long? = null)

    @Query("UPDATE home_screen_grid_items SET `page` = `page` + :shiftBy WHERE `page` > :shiftFrom")
    fun shiftPage(shiftFrom: Int, shiftBy: Int)

    @Transaction
    fun eraseByPackageName(packageName: String) {
        eraseItemByPackageName(packageName)
        eraseItemsByParentPackageName(packageName)
    }
}
