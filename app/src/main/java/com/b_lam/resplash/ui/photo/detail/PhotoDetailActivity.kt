package com.b_lam.resplash.ui.photo.detail

import android.Manifest
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.observe
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.b_lam.resplash.R
import com.b_lam.resplash.data.photo.model.Photo
import com.b_lam.resplash.ui.base.BaseActivity
import com.b_lam.resplash.ui.collection.add.AddCollectionBottomSheet
import com.b_lam.resplash.ui.login.LoginActivity
import com.b_lam.resplash.ui.photo.zoom.PhotoZoomActivity
import com.b_lam.resplash.ui.search.SearchActivity
import com.b_lam.resplash.ui.user.UserActivity
import com.b_lam.resplash.ui.widget.recyclerview.SpacingItemDecoration
import com.b_lam.resplash.util.*
import com.b_lam.resplash.util.customtabs.CustomTabsHelper
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import kotlinx.android.synthetic.main.activity_photo_detail.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class PhotoDetailActivity : BaseActivity(), TagAdapter.ItemEventCallback {

    override val viewModel: PhotoDetailViewModel by viewModel()

    private lateinit var id: String

    private val compositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_detail)

        setupActionBar(R.id.toolbar) {
            title = ""
            setDisplayHomeAsUpEnabled(true)
        }

        scroll_view.doOnApplyWindowInsets { view, _, _ -> view.updatePadding(top = 0) }
        constraint_layout.doOnApplyWindowInsets { view, _, _ -> view.updatePadding(top = 0) }
        photo_image_view.doOnApplyWindowInsets { view, _, _ -> view.updatePadding(top = 0) }

        val photo = intent.getParcelableExtra<Photo>(EXTRA_PHOTO)
        val photoId = intent.getStringExtra(EXTRA_PHOTO_ID)

        when {
            photo != null -> id = photo.id
            photoId != null -> id = photoId
            else -> null
        } ?.let {
            if (photo != null) setup(photo)
            viewModel.photoDetailsLiveData(id).observe(this) { photoDetails ->
                if (photo == null) { setup(photoDetails) }
                displayPhotoDetails(photoDetails)
            }
        } ?: run {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_photo_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_in_browser -> {
                openPhotoInBrowser()
                true
            }
            R.id.action_share -> {
                sharePhoto()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setup(photo: Photo) {
        val url = getPhotoUrl(photo, sharedPreferencesRepository.loadQuality)
        photo_image_view.loadPhotoUrlWithThumbnail(url, photo.urls.thumb, photo.color, centerCrop = true)
        photo_image_view.setOnClickListener {
            Intent(this, PhotoZoomActivity::class.java).apply {
                putExtra(PhotoZoomActivity.EXTRA_PHOTO_URL, url)
                startActivity(this)
            }
        }
    }

    private fun displayPhotoDetails(photo: Photo) {
        content_loading_layout.hide()
        photo.user?.let { user ->
            user_text_view.text = user.name ?: getString(R.string.unknown)
            user_image_view.loadProfilePicture(user)
            user_container.setOnClickListener {
                Intent(this, UserActivity::class.java).apply {
                    putExtra(UserActivity.EXTRA_USER, user)
                    startActivity(this)
                }
            }
        }
        photo.location?.let { location ->
            val locationString = when {
                location.city != null && location.country != null ->
                    getString(R.string.location_template, location.city, location.country)
                location.city != null && location.country == null -> location.city
                location.city == null && location.country != null -> location.country
                else -> null
            }
            location_text_view.setTextAndVisibility(locationString)
            location_text_view.setOnClickListener { locationString?.let { openLocationInMaps(it) } }
        }
        exif_recycler_view.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = ExifAdapter(context).apply { setExif(photo) }
        }
        views_count_text_view.text = (photo.views ?: 0).toPrettyString()
        downloads_count_text_view.text = (photo.downloads ?: 0).toPrettyString()
        likes_count_text_view.text = (photo.likes ?: 0).toPrettyString()
        tag_recycler_view.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false).apply {
                addItemDecoration(SpacingItemDecoration(context, R.dimen.keyline_6, RecyclerView.HORIZONTAL))
            }
            adapter = TagAdapter(this@PhotoDetailActivity).apply { submitList(photo.tags) }
        }

        collect_button.setOnClickListener {
            if (viewModel.isUserAuthorized()) {
                AddCollectionBottomSheet
                    .newInstance(photo)
                    .show(supportFragmentManager, AddCollectionBottomSheet.TAG)
            } else {
                toast(R.string.need_to_log_in)
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        setLikeButtonState(photo.liked_by_user ?: false)
        like_button.setOnClickListener {
            if (viewModel.isUserAuthorized()) {
                if (photo.liked_by_user == true) {
                    viewModel.unlikePhoto(photo.id)
                } else {
                    viewModel.likePhoto(photo.id)
                }
                photo.liked_by_user = photo.liked_by_user?.not()
                setLikeButtonState(photo.liked_by_user ?: false)
            } else {
                toast(R.string.need_to_log_in)
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
        download_button.setOnClickListener { downloadPhoto(photo) }
        set_as_wallpaper_button.setOnClickListener { setWallpaper(photo) }
        set_as_wallpaper_button.show()
    }

    private fun downloadPhoto(photo: Photo) {
        if (hasWritePermission()) {
            val downloadManager: RxDownloadManager by inject()
            compositeDisposable += downloadManager.downloadPhoto(
                getPhotoUrl(photo, sharedPreferencesRepository.downloadQuality),
                photo.fileName
            ).second.doOnSubscribe {
                toast(R.string.download_started)
            }.doAfterTerminate {
                compositeDisposable.clear()
            }.subscribeBy(
                onNext = {
                    viewModel.trackDownload(photo.id)
                    toast(R.string.download_complete)
                },
                onError = { toast(R.string.oops) }
            )
        } else {
            requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, requestCode = 0)
        }
    }

    private fun setWallpaper(photo: Photo) {
        if (hasWritePermission()) {
            val downloadManager: RxDownloadManager by inject()
            val download = downloadManager.downloadWallpaper(
                getPhotoUrl(photo, sharedPreferencesRepository.wallpaperQuality),
                photo.fileName)

            val snackbar = Snackbar
                .make(coordinator_layout, R.string.setting_wallpaper, Snackbar.LENGTH_INDEFINITE)
                .setAnchorView(R.id.set_as_wallpaper_button)
                .setAction(R.string.cancel) { downloadManager.cancelDownload(download.first) }
                .setActionTextColor(ContextCompat.getColor(this, R.color.red_400))

            compositeDisposable += download.second
                .doOnSubscribe { snackbar.show() }
                .doAfterTerminate {
                    snackbar.dismiss()
                    compositeDisposable.clear()
                }
                .subscribeBy(
                    onNext = {
                        viewModel.trackDownload(photo.id)
                        startActivity(WallpaperManager.getInstance(this).getCropAndSetWallpaperIntent(it))
                    },
                    onError = {}
                )
        } else {
            requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, requestCode = 0)
        }
    }

    override fun onTagClick(tag: String) {
        Intent(this, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_SEARCH_QUERY, tag)
            startActivity(this)
        }
    }

    private fun setLikeButtonState(likedByUser: Boolean) {
        like_button.setImageResource(
            if (likedByUser) R.drawable.ic_favorite_filled_24dp
            else R.drawable.ic_favorite_border_24dp
        )
    }

    private fun openPhotoInBrowser() {
        val uri = Uri.parse(viewModel.photoDetailsLiveData(id).value?.links?.html)
        CustomTabsHelper.openCustomTab(this, uri, sharedPreferencesRepository.theme)
    }

    private fun sharePhoto() {
        val share = Intent.createChooser(Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, viewModel.photoDetailsLiveData(id).value?.links?.html)
            putExtra(Intent.EXTRA_TITLE, viewModel.photoDetailsLiveData(id).value?.description)
        }, null)
        startActivity(share)
    }

    companion object {

        // Used to pass entire photo object to display while getting details
        const val EXTRA_PHOTO = "extra_photo"

        // Used to pass get photo details when coming from Auto Wallpaper History or externally
        const val EXTRA_PHOTO_ID = "extra_photo_id"
    }
}
