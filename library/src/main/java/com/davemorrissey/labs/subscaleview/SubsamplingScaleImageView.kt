package com.davemorrissey.labs.subscaleview

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.annotation.AnyThread
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.CheckResult
import androidx.annotation.ColorInt
import androidx.core.view.ViewConfigurationCompat
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.toUri
import com.davemorrissey.labs.subscaleview.internal.Anim
import com.davemorrissey.labs.subscaleview.internal.ClearingLifecycleObserver
import com.davemorrissey.labs.subscaleview.internal.CompositeImageEventListener
import com.davemorrissey.labs.subscaleview.internal.GestureListener
import com.davemorrissey.labs.subscaleview.internal.InternalErrorHandler
import com.davemorrissey.labs.subscaleview.internal.ScaleAndTranslate
import com.davemorrissey.labs.subscaleview.internal.Tile
import com.davemorrissey.labs.subscaleview.internal.TileMap
import com.davemorrissey.labs.subscaleview.internal.TouchEventDelegate
import com.davemorrissey.labs.subscaleview.internal.getExifOrientation
import com.davemorrissey.labs.subscaleview.internal.isTilingEnabled
import com.davemorrissey.labs.subscaleview.internal.panBy
import com.davemorrissey.labs.subscaleview.internal.sHeight
import com.davemorrissey.labs.subscaleview.internal.sWidth
import com.davemorrissey.labs.subscaleview.internal.scaleBy
import com.davemorrissey.labs.subscaleview.internal.setMatrixArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.min
import kotlin.math.roundToInt

@Suppress("MemberVisibilityCanBePrivate")
public open class SubsamplingScaleImageView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	// Bitmap (preview or full image)
	private var bitmap: Bitmap? = null

	// Whether the bitmap is a preview image
	private var bitmapIsPreview: Boolean = false

	// Specifies if a cache handler is also referencing the bitmap. Do not recycle if so.
	private var bitmapIsCached: Boolean = false

	// Uri of full size image
	private var uri: Uri? = null

	// Sample size used to display the whole image when fully zoomed out
	private var fullImageSampleSize: Int = 0

	// Map of zoom level to tile grid
	private var tileMap: TileMap? = null

	// BUG 2 FIX: generation counter. Incremented every time tiles are invalidated.
	// Each loadTile() coroutine captures the generation at launch; if the generation
	// has changed by the time the bitmap is decoded, it's discarded instead of stored.
	// This eliminates the race where low-res tiles (loaded at downSampling=4) overwrite
	// freshly loaded high-res tiles (at downSampling=1) after a resume event.
	private val tileGeneration = AtomicInteger(0)

	private var _downSampling = 1
	public var downSampling: Int = _downSampling
		set(value) {
			require(value > 0 && value.countOneBits() == 1) {
				"Downsampling value must be a positive power of 2"
			}
			if (field != value) {
				field = value
				invalidateTiles()
			}
		}

	// Image orientation setting
	public var orientation: Int = ORIENTATION_0
		set(value) {
			require(value in VALID_ORIENTATIONS)
			field = value
			reset(false)
			invalidate()
			requestLayout()
		}

	// Max scale allowed (prevent infinite zoom)
	public var maxScale: Float = 2F

	// Min scale allowed (prevent infinite zoom)
	private var _minScale: Float = minScale()

	public var minScale: Float
		get() = minScale()
		set(value) {
			_minScale = value
		}

	// Density to reach before loading higher resolution tiles
	private var minimumTileDpi: Int = -1

	// Pan limiting style
	public var panLimit: Int = PAN_LIMIT_INSIDE
		set(value) {
			require(value in VALID_PAN_LIMITS) { "Invalid pan limit: $value" }
			field = value
			if (isReady) {
				fitToBounds(true)
				invalidate()
			}
		}

	@get:ColorInt
	public var tileBackgroundColor: Int
		get() = tileBgPaint?.color ?: Color.TRANSPARENT
		set(@ColorInt value) {
			tileBgPaint = if (Color.alpha(value) == 0) {
				null
			} else {
				Paint().apply {
					style = Paint.Style.FILL
					color = value
				}
			}
			invalidate()
		}

	// Minimum scale type
	public var minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE
		set(value) {
			require(value in VALID_SCALE_TYPES) { "Invalid scale type: $value" }
			field = value
			if (isReady) {
				fitToBounds(true)
				invalidate()
			}
		}

	// overrides for the dimensions of the generated tiles
	private var maxTileWidth: Int = TILE_SIZE_AUTO
	private var maxTileHeight: Int = TILE_SIZE_AUTO

	public var backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default

	// Whether tiles should be loaded while gestures and animations are still in progress
	public var isEagerLoadingEnabled: Boolean = true

	// Gesture detection settings
	public var isPanEnabled: Boolean = true
		set(value) {
			field = value
			if (!value) {
				vTranslate?.set(
					width / 2f - scale * (sWidth() / 2f),
					height / 2f - scale * (sHeight() / 2f),
				)
				if (isReady) {
					refreshRequiredTiles(load = true)
					invalidate()
				}
			}
		}
	public var isZoomEnabled: Boolean = true
	public var isQuickScaleEnabled: Boolean = true

	// Double tap zoom behaviour
	public var doubleTapZoomScale: Float = 1F
	public var doubleTapZoomStyle: Int = ZOOM_FOCUS_FIXED
		set(value) {
			require(value in VALID_ZOOM_STYLES)
			field = value
		}
	private var doubleTapZoomDuration: Int = 500

	// Current scale and scale at start of zoom
	public var scale: Float = 0f
		@JvmSynthetic
		internal set

	@JvmField
	@JvmSynthetic
	internal var scaleStart: Float = 0f

	// Screen coordinate of top-left corner of source image
	@JvmField
	@JvmSynthetic
	internal var vTranslate: PointF? = null

	@JvmField
	@JvmSynthetic
	internal var vTranslateStart: PointF? = null
	private var vTranslateBefore: PointF? = null

	// Source coordinate to center on, used when new position is set externally before view is ready
	private var pendingScale: Float? = null
	private var sPendingCenter: PointF? = null

	@JvmField
	@JvmSynthetic
	internal var sRequestedCenter: PointF? = null

	// Source image dimensions and orientation - dimensions relate to the unrotated image
	public var sWidth: Int = 0
		private set
	public var sHeight: Int = 0
		private set
	private var sOrientation: Int = 0
	private var sRegion: Rect? = null
	private var pRegion: Rect? = null

	// Is two-finger zooming in progress
	@JvmField
	@JvmSynthetic
	internal var isZooming: Boolean = false

	// Is one-finger panning in progress
	@JvmField
	@JvmSynthetic
	internal var isPanning: Boolean = false

	// Is quick-scale gesture in progress
	@JvmField
	@JvmSynthetic
	internal var isQuickScaling: Boolean = false

	// Fling detector
	private var detector: GestureDetector? = null
	private var singleDetector: GestureDetector? = null

	// Tile and image decoding
	private var decoder: ImageRegionDecoder? = null
	private val decoderLock = ReentrantReadWriteLock(true)
	// Decoder factories. Both default to BitmapQuality.STANDARD (ARGB_8888), which is
	// the correct color depth for manga/webtoon — 8 bits per channel, full alpha, no
	// visible banding. Override to use BitmapQuality.HIGH (RGBA_F16, API 26+) for HDR
	// content or BitmapQuality.MEMORY_SAVING (RGB_565) for severely RAM-limited devices.
	public var bitmapDecoderFactory: DecoderFactory<out ImageDecoder> = SkiaImageDecoder.Factory()
	public var regionDecoderFactory: DecoderFactory<out ImageRegionDecoder> = SkiaImageRegionDecoder.Factory()

	// Debug values
	protected val isDebugDrawingEnabled: Boolean
		get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			isShowingLayoutBounds
		} else {
			false
		}

	@JvmField
	@JvmSynthetic
	internal var vCenterStart: PointF? = null

	@JvmField
	@JvmSynthetic
	internal var vDistStart: Float = 0f

	@JvmField
	@JvmSynthetic
	internal var quickScaleLastDistance: Float = 0f

	@JvmField
	@JvmSynthetic
	internal var quickScaleMoved: Boolean = false

	@JvmField
	@JvmSynthetic
	internal var quickScaleVLastPoint: PointF? = null

	@JvmField
	@JvmSynthetic
	internal var quickScaleSCenter: PointF? = null

	@JvmField
	@JvmSynthetic
	internal var quickScaleVStart: PointF? = null

	// Scale and center animation tracking
	@JvmField
	@JvmSynthetic
	internal var anim: Anim? = null

	// Whether a ready notification has been sent to subclasses
	@JvmField
	@JvmSynthetic
	internal var isReadySent: Boolean = false

	// Whether a base layer loaded notification has been sent to subclasses
	private var imageLoadedSent: Boolean = false

	// Event listener
	private val onImageEventListeners = CompositeImageEventListener()

	protected val onImageEventListener: OnImageEventListener
		get() = onImageEventListeners

	// Scale and center listener
	public var onStateChangedListener: OnStateChangedListener? = null

	@Suppress("LeakingThis")
	private val touchEventDelegate = TouchEventDelegate(this)

	// Paint objects created once and reused for efficiency
	private var bitmapPaint: Paint? = null
	private var debugTextPaint: Paint? = null
	private var debugLinePaint: Paint? = null
	private var tileBgPaint: Paint? = null

	public var colorFilter: ColorFilter? = null
		set(value) {
			field = value
			bitmapPaint?.colorFilter = value
		}

	private var pendingState: ImageViewState? = null

	private var stateRestoreStrategy: Int = RESTORE_STRATEGY_NONE

	// Volatile fields used to reduce object creation
	private var satTemp: ScaleAndTranslate? = null
	private var matrix2: Matrix? = null
	private var sRect: RectF? = null
	private val srcArray = FloatArray(8)
	private val dstArray = FloatArray(8)

	public val isReady: Boolean
		get() = isReadySent

	// The logical density of the display
	private val density = context.resources.displayMetrics.density
	private val viewConfig = ViewConfiguration.get(context)
	protected val coroutineScope: CoroutineScope = CoroutineScope(
		Dispatchers.Main.immediate + InternalErrorHandler() + SupervisorJob(),
	)

	init {
		setMinimumDpi(160)
		setDoubleTapZoomDpi(160)
		setMinimumTileDpi(320)
		setGestureDetector(context)
		val ta = context.obtainStyledAttributes(attrs, R.styleable.SubsamplingScaleImageView, defStyleAttr, 0)
		downSampling = ta.getInt(R.styleable.SubsamplingScaleImageView_downSampling, downSampling)
		isPanEnabled = ta.getBoolean(R.styleable.SubsamplingScaleImageView_panEnabled, isPanEnabled)
		isZoomEnabled = ta.getBoolean(R.styleable.SubsamplingScaleImageView_zoomEnabled, isZoomEnabled)
		doubleTapZoomStyle = ta.getInt(R.styleable.SubsamplingScaleImageView_doubleTapZoomStyle, doubleTapZoomStyle)
		isQuickScaleEnabled =
			ta.getBoolean(R.styleable.SubsamplingScaleImageView_quickScaleEnabled, isQuickScaleEnabled)
		panLimit = ta.getInt(R.styleable.SubsamplingScaleImageView_panLimit, panLimit)
		stateRestoreStrategy = ta.getInt(R.styleable.SubsamplingScaleImageView_restoreStrategy, stateRestoreStrategy)
		tileBackgroundColor = ta.getColor(
			R.styleable.SubsamplingScaleImageView_tileBackgroundColor,
			Color.TRANSPARENT,
		)
		val assetName = ta.getString(R.styleable.SubsamplingScaleImageView_assetName)
		if (!assetName.isNullOrBlank()) {
			setImage(ImageSource.asset(assetName))
		} else {
			val resId = ta.getResourceId(R.styleable.SubsamplingScaleImageView_src, 0)
			if (resId != 0) {
				setImage(ImageSource.resource(resId))
			}
		}
		ta.recycle()
	}

	@JvmOverloads
	public fun setImage(imageSource: ImageSource, previewSource: ImageSource? = null, state: ImageViewState? = null) {
		reset(true)
		state?.let { restoreState(it) }
		pendingState?.let { restoreState(it) }

		if (previewSource != null) {
			require(imageSource !is ImageSource.Bitmap) {
				"Preview image cannot be used when a bitmap is provided for the main image"
			}
			require(imageSource.sWidth > 0 && imageSource.sHeight > 0) {
				"Preview image cannot be used unless dimensions are provided for the main image"
			}
			this.sWidth = imageSource.sWidth
			this.sHeight = imageSource.sHeight
			this.pRegion = previewSource.region
			when (previewSource) {
				is ImageSource.Bitmap -> {
					this.bitmapIsCached = previewSource.isCached
					onPreviewLoaded(previewSource.bitmap)
				}

				else -> {
					val uri = (previewSource as? ImageSource.Uri)?.uri ?: Uri.parse(
						ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" +
							(previewSource as ImageSource.Resource).resourceId,
					)
					loadBitmap(uri, true)
				}
			}
		}

		when (imageSource) {
			is ImageSource.Bitmap -> {
				val region = imageSource.region
				if (region != null) {
					onImageLoaded(
						Bitmap.createBitmap(
							imageSource.bitmap,
							region.left,
							region.right,
							region.width(),
							region.height(),
						),
						ORIENTATION_0,
						false,
					)
				} else {
					onImageLoaded(imageSource.bitmap, ORIENTATION_0, imageSource.isCached)
				}
			}

			else -> {
				sRegion = imageSource.region
				uri = imageSource.toUri(context).also { uri ->
					if (imageSource.isTilingEnabled || sRegion != null) {
						// Load the bitmap using tile decoding.
						initTiles(regionDecoderFactory, uri)
					} else {
						// Load the bitmap as a single image.
						loadBitmap(uri, false)
					}
				}
			}
		}
	}

	@CallSuper
	public open fun recycle() {
		reset(true)
		bitmapPaint = null
		debugTextPaint = null
		debugLinePaint = null
		tileBgPaint = null
	}

	@CheckResult
	public fun snapshot(config: Bitmap.Config? = null): Bitmap? = bitmap?.let {
		if (!it.isRecycled) {
			it.copy(config ?: it.config, false)
		} else {
			null
		}
	}

	public fun setMinimumDpi(dpi: Int) {
		val metrics = resources.displayMetrics
		val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
		maxScale = averageDpi / dpi
	}

	public fun setMaximumDpi(dpi: Int) {
		val metrics = resources.displayMetrics
		val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
		_minScale = averageDpi / dpi
	}

	public fun setMinimumTileDpi(minimumTileDpi: Int) {
		val metrics = resources.displayMetrics
		val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
		this.minimumTileDpi = minOf(averageDpi, minimumTileDpi.toFloat()).toInt()
		if (isReady) {
			reset(false)
			invalidate()
		}
	}

	public fun setDoubleTapZoomDpi(dpi: Int) {
		val metrics = resources.displayMetrics
		val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
		doubleTapZoomScale = averageDpi / dpi
	}

	@JvmSynthetic
	internal fun setGestureDetector(context: Context) {
		detector = GestureDetector(context, GestureListener(this))
		singleDetector = GestureDetector(
			context,
			object : SimpleOnGestureListener() {
				override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
					performClick()
					return true
				}
			},
		)
	}

	override fun setOnLongClickListener(listener: OnLongClickListener?) {
		if (!isLongClickable) {
			isLongClickable = true
		}
		touchEventDelegate.onLongClickListener = listener
	}

	@Deprecated("Use addOnImageEventListener() instead")
	public fun setOnImageEventListener(listener: OnImageEventListener?) {
		onImageEventListeners.clearListeners()
		if (listener != null) {
			onImageEventListeners.addListener(listener)
		}
	}

	public fun addOnImageEventListener(listener: OnImageEventListener) {
		onImageEventListeners.addListener(listener)
	}

	public fun removeOnImageEventListener(listener: OnImageEventListener) {
		onImageEventListeners.removeListener(listener)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		val sCenter = getCenter()
		if (isReadySent && sCenter != null) {
			anim = null
			pendingScale = scale
			sPendingCenter = sCenter
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
		val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
		val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
		val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
		val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
		val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
		var width = parentWidth
		var height = parentHeight
		if (sWidth > 0 && sHeight > 0) {
			if (resizeWidth && resizeHeight) {
				width = sWidth()
				height = sHeight()
			} else if (resizeHeight) {
				height = (sHeight().toDouble() / sWidth().toDouble() * width).toInt()
			} else if (resizeWidth) {
				width = (sWidth().toDouble() / sHeight().toDouble() * height).toInt()
			}
		}
		width = width.coerceAtLeast(suggestedMinimumWidth)
		height = height.coerceAtLeast(suggestedMinimumHeight)
		setMeasuredDimension(width, height)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		createPaints()

		if ((sWidth == 0) || (sHeight == 0) || (width == 0) || (height == 0)) {
			return
		}

		// BUG 1 FIX: initialiseBaseLayer is now also triggered from onTilesInited,
		// so this check becomes a no-op for the first draw in most cases.
		if (tileMap == null && decoder != null) {
			initialiseBaseLayer(getMaxBitmapDimensions(canvas))
		}

		if (!checkReady()) {
			return
		}

		preDraw()
		processAnimation()

		val tiles = tileMap
		if (tiles != null && isBaseLayerReady()) {
			val sampleSize = calculateInSampleSize(scale).coerceAtMost(fullImageSampleSize)
			val hasMissingTiles = tiles.hasMissingTiles(sampleSize)

			for ((key, value) in tiles) {
				if (key == sampleSize || hasMissingTiles) {
					for (tile in value) {
						sourceToViewRect(tile.sRect, tile.vRect)
						// Snapshot tile.bitmap to a local val. Tile.bitmap is @Volatile and
						// written by background coroutines; snapshotting once prevents a race
						// between the null-check here and the subsequent !! dereference below.
						val tileBitmap = tile.bitmap
						if (tileBitmap != null && !tileBitmap.isRecycled) {
							tileBgPaint?.let {
								canvas.drawRect(tile.vRect, it)
							}
							if (matrix2 == null) {
								matrix2 = Matrix()
							}
							matrix2!!.reset()
							setMatrixArray(
								srcArray,
								0f,
								0f,
								tileBitmap.width.toFloat(),
								0f,
								tileBitmap.width.toFloat(),
								tileBitmap.height.toFloat(),
								0f,
								tileBitmap.height.toFloat(),
							)
							if (getRequiredRotation() == ORIENTATION_0) {
								setMatrixArray(
									dstArray,
									tile.vRect.left.toFloat(),
									tile.vRect.top.toFloat(),
									tile.vRect.right.toFloat(),
									tile.vRect.top.toFloat(),
									tile.vRect.right.toFloat(),
									tile.vRect.bottom.toFloat(),
									tile.vRect.left.toFloat(),
									tile.vRect.bottom.toFloat(),
								)
							} else if (getRequiredRotation() == ORIENTATION_90) {
								setMatrixArray(
									dstArray,
									tile.vRect.right.toFloat(),
									tile.vRect.top.toFloat(),
									tile.vRect.right.toFloat(),
									tile.vRect.bottom.toFloat(),
									tile.vRect.left.toFloat(),
									tile.vRect.bottom.toFloat(),
									tile.vRect.left.toFloat(),
									tile.vRect.top.toFloat(),
								)
							} else if (getRequiredRotation() == ORIENTATION_180) {
								setMatrixArray(
									dstArray,
									tile.vRect.right.toFloat(),
									tile.vRect.bottom.toFloat(),
									tile.vRect.left.toFloat(),
									tile.vRect.bottom.toFloat(),
									tile.vRect.left.toFloat(),
									tile.vRect.top.toFloat(),
									tile.vRect.right.toFloat(),
									tile.vRect.top.toFloat(),
								)
							} else if (getRequiredRotation() == ORIENTATION_270) {
								setMatrixArray(
									dstArray,
									tile.vRect.left.toFloat(),
									tile.vRect.bottom.toFloat(),
									tile.vRect.left.toFloat(),
									tile.vRect.top.toFloat(),
									tile.vRect.right.toFloat(),
									tile.vRect.top.toFloat(),
									tile.vRect.right.toFloat(),
									tile.vRect.bottom.toFloat(),
								)
							}
							matrix2!!.setPolyToPoly(srcArray, 0, dstArray, 0, 4)
							canvas.drawBitmap(tileBitmap, matrix2!!, bitmapPaint)
							if (isDebugDrawingEnabled) {
								canvas.drawRect(tile.vRect, debugLinePaint!!)
							}
						} else if (tile.isLoading && isDebugDrawingEnabled) {
							canvas.drawText(
								"LOADING",
								(tile.vRect.left + px(5)).toFloat(),
								(tile.vRect.top + px(35)).toFloat(),
								debugTextPaint!!,
							)
						}
						if (tile.isVisible && isDebugDrawingEnabled) {
							canvas.drawText(
								"ISS ${tile.sampleSize} RECT ${tile.sRect.top},${tile.sRect.left}," +
									"${tile.sRect.bottom},${tile.sRect.right}",
								(tile.vRect.left + px(5)).toFloat(),
								(tile.vRect.top + px(15)).toFloat(),
								debugTextPaint!!,
							)
						}
					}
				}
			}
		} else if (bitmap != null && !bitmap!!.isRecycled) {
			var xScale = scale
			var yScale = scale
			if (bitmapIsPreview) {
				xScale = scale * (sWidth.toFloat() / bitmap!!.width)
				yScale = scale * (sHeight.toFloat() / bitmap!!.height)
			} else if (_downSampling != 1) {
				xScale *= _downSampling
				yScale *= _downSampling
			}
			if (matrix2 == null) {
				matrix2 = Matrix()
			}
			matrix2!!.reset()
			matrix2!!.postScale(xScale, yScale)
			matrix2!!.postRotate(getRequiredRotation().toFloat())
			matrix2!!.postTranslate(vTranslate!!.x, vTranslate!!.y)
			if (getRequiredRotation() == ORIENTATION_180) {
				matrix2!!.postTranslate(scale * sWidth, scale * sHeight)
			} else if (getRequiredRotation() == ORIENTATION_90) {
				matrix2!!.postTranslate(scale * sHeight, 0f)
			} else if (getRequiredRotation() == ORIENTATION_270) {
				matrix2!!.postTranslate(0f, scale * sWidth)
			}
			if (tileBgPaint != null) {
				if (sRect == null) {
					sRect = RectF()
				}
				sRect!!.set(
					0f,
					0f,
					(if (bitmapIsPreview) bitmap!!.width else sWidth).toFloat(),
					(if (bitmapIsPreview) bitmap!!.height else sHeight).toFloat(),
				)
				matrix2!!.mapRect(sRect)
				canvas.drawRect(sRect!!, tileBgPaint!!)
			}
			canvas.drawBitmap(bitmap!!, matrix2!!, bitmapPaint)
		}
		if (isDebugDrawingEnabled) {
			canvas.drawText(
				"Scale: " + String.format(Locale.ENGLISH, "%.2f", scale) + " (" + String.format(
					Locale.ENGLISH,
					"%.2f",
					minScale(),
				) + " - " + String.format(
					Locale.ENGLISH,
					"%.2f",
					maxScale,
				) + ")",
				px(5).toFloat(),
				px(15).toFloat(),
				debugTextPaint!!,
			)
			canvas.drawText(
				"Translate: " + String.format(Locale.ENGLISH, "%.2f", vTranslate!!.x) + ":" + String.format(
					Locale.ENGLISH,
					"%.2f",
					vTranslate!!.y,
				),
				px(5).toFloat(),
				px(30).toFloat(),
				debugTextPaint!!,
			)
			val center = getCenter()
			canvas.drawText(
				"Source center: " + String.format(Locale.ENGLISH, "%.2f", center!!.x) + ":" + String.format(
					Locale.ENGLISH,
					"%.2f",
					center.y,
				),
				px(5).toFloat(),
				px(45).toFloat(),
				debugTextPaint!!,
			)
		}
	}

	private fun getMaxBitmapDimensions(canvas: Canvas) = Point(
		minOf(canvas.maximumBitmapWidth, maxTileWidth),
		minOf(canvas.maximumBitmapHeight, maxTileHeight),
	)

	@Synchronized
	private fun initialiseBaseLayer(maxTileDimensions: Point) {
		satTemp = ScaleAndTranslate(0f, PointF(0f, 0f))
		fitToBounds(true, satTemp!!)

		fullImageSampleSize = calculateInSampleSize(satTemp!!.scale)
		if (fullImageSampleSize > 1) {
			fullImageSampleSize /= 2
		}
		if (fullImageSampleSize == 1 && sRegion == null && sWidth() < maxTileDimensions.x && sHeight() < maxTileDimensions.y) {
			decoder?.recycle()
			decoder = null
			loadBitmap(uri!!, false)
		} else {
			initialiseTileMap(maxTileDimensions)
			val baseGrid: List<Tile>? = tileMap!![fullImageSampleSize]
			if (baseGrid != null) {
				for (baseTile in baseGrid) {
					loadTile(decoder!!, baseTile)
				}
			}
			refreshRequiredTiles(load = true)
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		anim = if (anim != null && !anim!!.interruptible) {
			requestDisallowInterceptTouchEvent(true)
			return true
		} else {
			try {
				anim?.listener?.onInterruptedByUser()
			} catch (e: Exception) {
				Log.w(TAG, "Error thrown by animation listener", e)
			}
			null
		}

		if (vTranslate == null) {
			singleDetector?.onTouchEvent(event)
			return true
		}
		if (!isQuickScaling && detector?.onTouchEvent(event) != false) {
			isZooming = false
			isPanning = false
			touchEventDelegate.reset()
			return true
		}
		if (vTranslateStart == null) {
			vTranslateStart = PointF(0f, 0f)
		}
		if (vTranslateBefore == null) {
			vTranslateBefore = PointF(0f, 0f)
		}
		if (vCenterStart == null) {
			vCenterStart = PointF(0f, 0f)
		}

		val scaleBefore = scale
		vTranslateBefore!!.set(vTranslate!!)
		val handled = touchEventDelegate.dispatchTouchEvent(event)
		sendStateChanged(scaleBefore, checkNotNull(vTranslateBefore), ORIGIN_TOUCH)
		return handled || super.onTouchEvent(event)
	}

	@JvmSynthetic
	internal fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
		parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
	}

	@JvmOverloads
	@CheckResult
	public fun getCenter(outPoint: PointF = PointF()): PointF? {
		val mX = width / 2
		val mY = height / 2
		return viewToSourceCoord(mX.toFloat(), mY.toFloat(), outPoint)
	}

	public fun viewToSourceCoord(vxy: PointF): PointF? {
		return viewToSourceCoord(vxy.x, vxy.y, PointF())
	}

	public fun viewToSourceCoord(vx: Float, vy: Float): PointF? {
		return viewToSourceCoord(vx, vy, PointF())
	}

	private fun initialiseTileMap(maxTileDimensions: Point) {
		tileMap?.recycleAll()
		tileMap = TileMap()
		var sampleSize = fullImageSampleSize
		var xTiles = 1
		var yTiles = 1
		while (true) {
			var sTileWidth = sWidth() / xTiles
			var sTileHeight = sHeight() / yTiles
			var subTileWidth = sTileWidth / sampleSize
			var subTileHeight = sTileHeight / sampleSize
			while (subTileWidth + xTiles + 1 > maxTileDimensions.x ||
				subTileWidth > width * 1.25 && sampleSize < fullImageSampleSize
			) {
				xTiles += 1
				sTileWidth = sWidth() / xTiles
				subTileWidth = sTileWidth / sampleSize
			}
			while (subTileHeight + yTiles + 1 > maxTileDimensions.y ||
				subTileHeight > height * 1.25 && sampleSize < fullImageSampleSize
			) {
				yTiles += 1
				sTileHeight = sHeight() / yTiles
				subTileHeight = sTileHeight / sampleSize
			}
			val tileGrid = ArrayList<Tile>(xTiles * yTiles)
			for (x in 0 until xTiles) {
				for (y in 0 until yTiles) {
					val tile = Tile()
					tile.sampleSize = sampleSize
					tile.isVisible = sampleSize == fullImageSampleSize
					tile.sRect.set(
						x * sTileWidth,
						y * sTileHeight,
						if (x == xTiles - 1) sWidth() else (x + 1) * sTileWidth,
						if (y == yTiles - 1) sHeight() else (y + 1) * sTileHeight,
					)
					tile.vRect.set(0, 0, 0, 0)
					tile.fileSRect.set(tile.sRect)
					tileGrid.add(tile)
				}
			}
			checkNotNull(tileMap)[sampleSize] = tileGrid
			sampleSize /= if (sampleSize == 1) {
				break
			} else {
				2
			}
		}
	}

	public fun viewToSourceCoord(vx: Float, vy: Float, sTarget: PointF): PointF? {
		if (vTranslate == null) {
			return null
		}
		sTarget[viewToSourceX(vx)] = viewToSourceY(vy)
		return sTarget
	}

	private fun sourceToViewX(sx: Float): Float {
		return sx * scale + (vTranslate ?: return Float.NaN).x
	}

	private fun sourceToViewY(sy: Float): Float {
		return sy * scale + (vTranslate ?: return Float.NaN).y
	}

	public fun sourceToViewCoord(sxy: PointF): PointF? {
		return sourceToViewCoord(sxy.x, sxy.y, PointF())
	}

	public fun sourceToViewCoord(sx: Float, sy: Float): PointF? {
		return sourceToViewCoord(sx, sy, PointF())
	}

	@CheckResult
	public fun sourceToViewCoord(sxy: PointF, vTarget: PointF): PointF? {
		return sourceToViewCoord(sxy.x, sxy.y, vTarget)
	}

	@CheckResult
	public fun sourceToViewCoord(sx: Float, sy: Float, vTarget: PointF): PointF? {
		if (vTranslate == null) {
			return null
		}
		vTarget[sourceToViewX(sx)] = sourceToViewY(sy)
		return vTarget
	}

	private fun sourceToViewRect(sRect: Rect, vTarget: Rect) {
		vTarget[
			sourceToViewX(sRect.left.toFloat()).toInt(), sourceToViewY(sRect.top.toFloat()).toInt(),
			sourceToViewX(
				sRect.right.toFloat(),
			).toInt(),
		] = sourceToViewY(sRect.bottom.toFloat()).toInt()
	}

	private fun viewToSourceX(vx: Float): Float {
		return (vx - (vTranslate ?: return Float.NaN).x) / scale
	}

	private fun viewToSourceY(vy: Float): Float {
		return (vy - (vTranslate ?: return Float.NaN).y) / scale
	}

	public fun bindToLifecycle(owner: LifecycleOwner) {
		owner.lifecycle.addObserver(ClearingLifecycleObserver(this))
	}

	internal fun cancelCoroutineScope() {
		coroutineScope.cancel()
	}

	private fun reset(isNewImage: Boolean) {
		scale = 0f
		scaleStart = 0f
		vTranslate = null
		vTranslateStart = null
		vTranslateBefore = null
		pendingScale = 0f
		sPendingCenter = null
		sRequestedCenter = null
		isZooming = false
		isPanning = false
		isQuickScaling = false
		touchEventDelegate.reset()
		fullImageSampleSize = 0
		vCenterStart = null
		vDistStart = 0f
		quickScaleLastDistance = 0f
		quickScaleMoved = false
		quickScaleSCenter = null
		quickScaleVLastPoint = null
		quickScaleVStart = null
		anim = null
		satTemp = null
		matrix2 = null
		sRect = null
		if (isNewImage) {
			coroutineScope.coroutineContext[Job]?.cancelChildren()
			// BUG 2 FIX: increment generation so any in-flight tile loads are discarded.
			tileGeneration.incrementAndGet()
			uri = null
			decoderLock.writeLock().lock()
			try {
				decoder?.recycle()
				decoder = null
			} finally {
				decoderLock.writeLock().unlock()
			}
			bitmap?.let {
				if (!bitmapIsCached) {
					it.recycle()
				} else {
					onImageEventListeners.onPreviewReleased()
				}
			}
			sWidth = 0
			sHeight = 0
			sOrientation = 0
			sRegion = null
			pRegion = null
			isReadySent = false
			imageLoadedSent = false
			bitmap = null
			bitmapIsPreview = false
			bitmapIsCached = false
		}
		tileMap?.recycleAll()
		tileMap = null
		setGestureDetector(context)
	}

	private fun restoreState(state: ImageViewState) {
		if (state.orientation in VALID_ORIENTATIONS) {
			pendingState = null
			orientation = state.orientation
			pendingScale = state.scale
			sPendingCenter = state.center
			invalidate()
		}
	}

	@JvmSynthetic
	internal fun refreshRequiredTiles(load: Boolean) {
		if (decoder == null) {
			return
		}
		val tiles = tileMap?.values ?: return
		val sampleSize = minOf(fullImageSampleSize, calculateInSampleSize(scale))

		for (value in tiles) {
			for (tile in value) {
				val isTileOutdated = !tile.isValid
				if (tile.sampleSize < sampleSize || tile.sampleSize > sampleSize && tile.sampleSize != fullImageSampleSize) {
					tile.recycle()
				}
				if (tile.sampleSize == sampleSize) {
					if (tileVisible(tile)) {
						tile.isVisible = true
						if (!tile.isLoading && (isTileOutdated || tile.bitmap == null) && load) {
							loadTile(decoder!!, tile)
						}
					} else if (tile.sampleSize != fullImageSampleSize) {
						tile.recycle()
					}
				} else if (tile.sampleSize == fullImageSampleSize) {
					tile.isVisible = true
				}
			}
		}
	}

	private fun invalidateTiles() {
		// BUG 2 FIX: increment generation before triggering new tile loads.
		// Any in-flight coroutines from the previous generation will see the
		// mismatch and discard their bitmaps rather than storing them.
		tileGeneration.incrementAndGet()
		tileMap?.invalidateAll()
		decoder?.let { _ ->
			_downSampling = downSampling
			refreshRequiredTiles(load = true)
			onDownSamplingChanged()
		} ?: uri?.let {
			loadBitmap(it, preview = false)
		} ?: run {
			_downSampling = downSampling
			onDownSamplingChanged()
		}
	}

	private fun tileVisible(tile: Tile): Boolean {
		val sVisLeft = viewToSourceX(0f)
		val sVisRight = viewToSourceX(width.toFloat())
		val sVisTop = viewToSourceY(0f)
		val sVisBottom = viewToSourceY(height.toFloat())
		val sRect = tile.sRect
		return !(sVisLeft > sRect.right || sRect.left > sVisRight || sVisTop > sRect.bottom || sRect.top > sVisBottom)
	}

	private fun calculateInSampleSize(scale: Float): Int {
		@Suppress("NAME_SHADOWING")
		var scale = scale
		if (minimumTileDpi > 0) {
			val metrics = resources.displayMetrics
			val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
			scale *= minimumTileDpi / averageDpi
		}
		val reqWidth = (sWidth() * scale).toInt()
		val reqHeight = (sHeight() * scale).toInt()

		var inSampleSize = 1
		if (reqWidth == 0 || reqHeight == 0) {
			return 32
		}
		if (sHeight() > reqHeight || sWidth() > reqWidth) {
			val heightRatio = (sHeight().toFloat() / reqHeight.toFloat()).roundToInt()
			val widthRatio = (sWidth().toFloat() / reqWidth.toFloat()).roundToInt()
			inSampleSize = min(heightRatio, widthRatio)
		}

		var power = 1
		while (power * 2 < inSampleSize) {
			power *= 2
		}
		return power
	}

	@JvmSynthetic
	internal fun minScale(): Float {
		val vPadding = paddingBottom + paddingTop
		val hPadding = paddingLeft + paddingRight
		return when {
			minimumScaleType == SCALE_TYPE_CENTER_CROP || minimumScaleType == SCALE_TYPE_START -> {
				maxOf((width - hPadding) / sWidth().toFloat(), (height - vPadding) / sHeight().toFloat())
			}

			minimumScaleType == SCALE_TYPE_CUSTOM && _minScale > 0 -> {
				_minScale
			}

			else -> {
				minOf((width - hPadding) / sWidth().toFloat(), (height - vPadding) / sHeight().toFloat())
			}
		}
	}

	@JvmSynthetic
	internal fun sWidth(): Int {
		val rotation: Int = getRequiredRotation()
		return if (rotation == 90 || rotation == 270) {
			sHeight
		} else {
			sWidth
		}
	}

	@JvmSynthetic
	internal fun sHeight(): Int {
		val rotation: Int = getRequiredRotation()
		return if (rotation == 90 || rotation == 270) {
			sWidth
		} else {
			sHeight
		}
	}

	@AnyThread
	private fun getRequiredRotation(): Int {
		return if (orientation == ORIENTATION_USE_EXIF) {
			sOrientation
		} else {
			orientation
		}
	}

	@Synchronized
	private fun onTileLoaded() {
		checkReady()
		checkImageLoaded()
		if (isBaseLayerReady() && bitmap != null) {
			if (!bitmapIsCached) {
				bitmap?.recycle()
			}
			bitmap = null
			if (bitmapIsCached) {
				onImageEventListeners.onPreviewReleased()
			}
			bitmapIsPreview = false
			bitmapIsCached = false
		}
		invalidate()
	}

	private fun loadBitmap(source: Uri, preview: Boolean) {
		coroutineScope.launch {
			try {
				val bitmap = async {
					runInterruptible(backgroundDispatcher) {
						bitmapDecoderFactory.make().decode(context, source, downSampling)
					}
				}
				val orientation = async {
					runInterruptible(backgroundDispatcher) {
						getExifOrientation(context, source)
					}
				}
				if (preview) {
					onPreviewLoaded(bitmap.await())
				} else {
					onImageLoaded(bitmap.await(), orientation.await(), false)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (error: Throwable) {
				Log.e(TAG, "Failed to load bitmap", error)
				if (preview) {
					onImageEventListeners.onPreviewLoadError(error)
				} else {
					onImageEventListeners.onImageLoadError(error)
				}
			}
		}
	}

	private fun initTiles(decoderFactory: DecoderFactory<out ImageRegionDecoder>, source: Uri) {
		coroutineScope.launch {
			try {
				val exifOrientation = async {
					runInterruptible(backgroundDispatcher) {
						getExifOrientation(context, source)
					}
				}
				val (w, h) = runInterruptible(backgroundDispatcher) {
					decoder = decoderFactory.make()
					val dimensions = checkNotNull(decoder).init(context, source)
					var sWidth = dimensions.x
					var sHeight = dimensions.y
					sRegion?.also {
						it.left = it.left.coerceAtLeast(0)
						it.top = it.top.coerceAtLeast(0)
						it.right = it.right.coerceAtMost(sWidth)
						it.bottom = it.bottom.coerceAtMost(sHeight)
						sWidth = it.width()
						sHeight = it.height()
					}
					sWidth to sHeight
				}
				onTilesInited(checkNotNull(decoder), w, h, exifOrientation.await())
			} catch (e: CancellationException) {
				throw e
			} catch (error: Throwable) {
				onImageEventListeners.onImageLoadError(error)
			}
		}
	}

	private fun loadTile(decoder: ImageRegionDecoder, tile: Tile) {
		tile.isLoading = true
		// Capture generation and downSampling at launch. If either changes while this
		// coroutine is running (because the user leaves the page or changes zoom level),
		// the decoded bitmap is stale and must be discarded rather than stored.
		val capturedGeneration = tileGeneration.get()
		val capturedDownSampling = downSampling
		coroutineScope.launch {
			try {
				val decodedBitmap: Bitmap? = if (decoder.isReady && tile.isVisible) {
					runInterruptible(backgroundDispatcher) {
						decoderLock.readLock().lock()
						try {
							if (decoder.isReady) {
								// Compute the file-coordinate rect for this tile, accounting
								// for image rotation. Capture downSampling from the closure —
								// reading it at decode time would race with applyDownSampling().
								fileSRect(tile.sRect, tile.fileSRect)
								sRegion?.let { tile.fileSRect.offset(it.left, it.top) }
								decoder.decodeRegion(tile.fileSRect, tile.sampleSize * capturedDownSampling)
							} else {
								tile.isLoading = false
								null
							}
						} finally {
							decoderLock.readLock().unlock()
						}
					}
				} else {
					tile.isLoading = false
					null
				}

				// Back on the main thread: check whether the generation is still current.
				if (tileGeneration.get() == capturedGeneration) {
					// Generation matches — store the bitmap and trigger a redraw.
					// Capture and recycle the old bitmap AFTER setting the new one, so
					// any concurrent onDraw() that reads tile.bitmap never sees a recycled
					// bitmap. The @Volatile annotation on Tile.bitmap ensures visibility.
					val oldBitmap = tile.bitmap
					tile.setBitmap(decodedBitmap)
					onTileLoaded()
					// Recycle the old bitmap on the main thread, after the new one is live.
					oldBitmap?.recycle()
				} else {
					// Generation changed while we were decoding: the bitmap is stale
					// (decoded at the wrong downSampling level). Discard it.
					decodedBitmap?.recycle()
					tile.isLoading = false
					// Mark as invalid so refreshRequiredTiles() requeues it at the correct
					// resolution on the next frame. Also trigger a redraw so the view
					// re-evaluates its tile state — without this, the view can stay black
					// if no other event fires another invalidate().
					tile.isValid = false
					invalidate()
				}
			} catch (e: CancellationException) {
				tile.isLoading = false
				throw e
			} catch (error: Throwable) {
				tile.isLoading = false
				onImageEventListeners.onTileLoadError(error)
			}
		}
	}

	@Synchronized
	private fun onTilesInited(decoder: ImageRegionDecoder, sWidth: Int, sHeight: Int, sOrientation: Int) {
		if ((sWidth > 0) && (this.sHeight > 0) && (this.sWidth != sWidth || this.sHeight != sHeight)) {
			reset(false)
			if (!bitmapIsCached) {
				bitmap?.recycle()
			}
			bitmap = null
			if (bitmapIsCached) {
				onImageEventListeners.onPreviewReleased()
			}
			bitmapIsPreview = false
			bitmapIsCached = false
		}
		this.decoder = decoder
		this.sWidth = sWidth
		this.sHeight = sHeight
		this.sOrientation = sOrientation
		checkReady()
		if (!checkImageLoaded() && maxTileWidth > 0 && maxTileWidth != TILE_SIZE_AUTO &&
			maxTileHeight > 0 && maxTileHeight != TILE_SIZE_AUTO && width > 0 && height > 0
		) {
			initialiseBaseLayer(Point(maxTileWidth, maxTileHeight))
		}
		invalidate()
		requestLayout()
	}

	@Synchronized
	private fun onPreviewLoaded(previewBitmap: Bitmap) {
		if (bitmap != null || imageLoadedSent) {
			previewBitmap.recycle()
			return
		}
		bitmap = pRegion?.let {
			Bitmap.createBitmap(previewBitmap, it.left, it.top, it.width(), it.height())
		} ?: previewBitmap
		bitmapIsPreview = true
		if (checkReady()) {
			invalidate()
			requestLayout()
		}
	}

	private fun isBaseLayerReady(): Boolean {
		if (bitmap != null && !bitmapIsPreview) {
			return true
		} else if (tileMap != null) {
			tileMap?.get(fullImageSampleSize)?.forEach { tile ->
				if (tile.bitmap == null) {
					return false
				}
			} ?: return false
			return true
		}
		return false
	}

	@AnyThread
	private fun fileSRect(sRect: Rect, target: Rect) {
		when (getRequiredRotation()) {
			ORIENTATION_0 -> target.set(sRect)
			ORIENTATION_90 -> target[sRect.top, sHeight - sRect.right, sRect.bottom] = sHeight - sRect.left
			ORIENTATION_180 -> target[sWidth - sRect.right, sHeight - sRect.bottom, sWidth - sRect.left] =
				sHeight - sRect.top

			else -> target[sWidth - sRect.bottom, sRect.left, sWidth - sRect.top] = sRect.right
		}
	}

	@Synchronized
	private fun onImageLoaded(bitmap: Bitmap, sOrientation: Int, bitmapIsCached: Boolean) {
		if (sWidth > 0 && sHeight > 0 && (sWidth != bitmap.width * downSampling || sHeight != bitmap.height * downSampling)) {
			reset(false)
		}
		this.bitmap?.let { oldBitmap ->
			if (this.bitmapIsCached) {
				onImageEventListeners.onPreviewReleased()
			} else {
				oldBitmap.recycle()
			}
		}
		bitmapIsPreview = false
		this.bitmapIsCached = bitmapIsCached
		this.bitmap = bitmap
		val isDownsamplingChanged = _downSampling != downSampling
		_downSampling = downSampling
		sWidth = bitmap.fullWidth()
		sHeight = bitmap.fullHeight()
		this.sOrientation = sOrientation
		if (isDownsamplingChanged) {
			onDownSamplingChanged()
		}
		val ready = checkReady()
		val imageLoaded = checkImageLoaded()
		if (ready || imageLoaded) {
			invalidate()
			requestLayout()
		}
	}

	private fun checkReady(): Boolean {
		val ready = width > 0 && height > 0 && sWidth > 0 && sHeight > 0 && (bitmap != null || isBaseLayerReady())
		if (!isReadySent && ready) {
			preDraw()
			isReadySent = true
			onReady()
			onImageEventListeners.onReady()
		}
		return ready
	}

	private fun checkImageLoaded(): Boolean {
		val imageLoaded: Boolean = isBaseLayerReady()
		if (!imageLoadedSent && imageLoaded) {
			preDraw()
			imageLoadedSent = true
			onImageLoaded()
			onImageEventListeners.onImageLoaded()
		}
		return imageLoaded
	}

	private fun sendStateChanged(oldScale: Float, oldVTranslate: PointF, origin: Int) {
		onStateChangedListener?.run {
			if (scale != oldScale) {
				@Suppress("DEPRECATION")
				onScaleChanged(scale, origin)
				onScaleChanged(this@SubsamplingScaleImageView, scale, origin)
			}
			if (vTranslate != oldVTranslate) {
				val center = getCenter()
				if (center != null) {
					@Suppress("DEPRECATION")
					onCenterChanged(center, origin)
					onCenterChanged(this@SubsamplingScaleImageView, center, origin)
				}
			}
		}
	}

	@JvmSynthetic
	internal fun fitToBounds(center: Boolean, sat: ScaleAndTranslate) {
		@Suppress("NAME_SHADOWING")
		var center = center
		if (panLimit == PAN_LIMIT_OUTSIDE && isReady) {
			center = false
		}
		val vTranslate = sat.vTranslate
		val scale: Float = limitedScale(sat.scale)
		val scaleWidth = scale * sWidth()
		val scaleHeight = scale * sHeight()
		when {
			panLimit == PAN_LIMIT_CENTER && isReady -> {
				vTranslate.x = vTranslate.x.coerceAtLeast(width / 2f - scaleWidth)
				vTranslate.y = vTranslate.y.coerceAtLeast(height / 2f - scaleHeight)
			}

			center -> {
				vTranslate.x = vTranslate.x.coerceAtLeast(width - scaleWidth)
				vTranslate.y = vTranslate.y.coerceAtLeast(height - scaleHeight)
			}

			else -> {
				vTranslate.x = vTranslate.x.coerceAtLeast(-scaleWidth)
				vTranslate.y = vTranslate.y.coerceAtLeast(-scaleHeight)
			}
		}

		val xPaddingRatio =
			if (paddingLeft > 0 || paddingRight > 0) paddingLeft / (paddingLeft + paddingRight).toFloat() else 0.5f
		val yPaddingRatio =
			if (paddingTop > 0 || paddingBottom > 0) paddingTop / (paddingTop + paddingBottom).toFloat() else 0.5f
		val maxTx: Float
		val maxTy: Float
		when {
			panLimit == PAN_LIMIT_CENTER && isReady -> {
				maxTx = (width / 2f).coerceAtLeast(0f)
				maxTy = (height / 2f).coerceAtLeast(0f)
			}

			center -> {
				maxTx = ((width - scaleWidth) * xPaddingRatio).coerceAtLeast(0f)
				maxTy = ((height - scaleHeight) * yPaddingRatio).coerceAtLeast(0f)
			}

			else -> {
				maxTx = width.toFloat().coerceAtLeast(0f)
				maxTy = height.toFloat().coerceAtLeast(0f)
			}
		}
		vTranslate.x = vTranslate.x.coerceAtMost(maxTx)
		vTranslate.y = vTranslate.y.coerceAtMost(maxTy)
		sat.scale = scale
	}

	@JvmSynthetic
	internal fun limitedScale(targetScale: Float): Float {
		return minOf(maxScale, maxOf(minScale(), targetScale))
	}

	@JvmSynthetic
	internal fun doubleTapZoom(sCenter: PointF, vFocus: PointF) {
		if (!isPanEnabled) {
			sRequestedCenter?.let {
				sCenter.x = it.x
				sCenter.y = it.y
			} ?: run {
				sCenter.x = sWidth() / 2f
				sCenter.y = sHeight() / 2f
			}
		}
		val doubleTapZoomScale = doubleTapZoomScale.coerceAtMost(maxScale)
		val zoomIn = scale <= doubleTapZoomScale * 0.9 || scale == _minScale
		val targetScale = if (zoomIn) doubleTapZoomScale else minScale()
		when {
			doubleTapZoomStyle == ZOOM_FOCUS_CENTER_IMMEDIATE -> {
				setScaleAndCenter(targetScale, sCenter)
			}

			doubleTapZoomStyle == ZOOM_FOCUS_CENTER || !zoomIn || !isPanEnabled -> {
				AnimationBuilder(this, targetScale, sCenter).withInterruptible(false)
					.withDuration(doubleTapZoomDuration.toLong())
					.withOrigin(ORIGIN_DOUBLE_TAP_ZOOM).start()
			}

			doubleTapZoomStyle == ZOOM_FOCUS_FIXED -> {
				AnimationBuilder(this, targetScale, sCenter, vFocus).withInterruptible(false)
					.withDuration(doubleTapZoomDuration.toLong())
					.withOrigin(ORIGIN_DOUBLE_TAP_ZOOM).start()
			}
		}
		invalidate()
	}

	private fun vTranslateForSCenter(sCenterX: Float, sCenterY: Float, scale: Float): PointF {
		val vxCenter = paddingLeft + (width - paddingRight - paddingLeft) / 2
		val vyCenter = paddingTop + (height - paddingBottom - paddingTop) / 2
		if (satTemp == null) {
			satTemp = ScaleAndTranslate(0f, PointF(0f, 0f))
		}
		satTemp!!.scale = scale
		satTemp!!.vTranslate[vxCenter - sCenterX * scale] = vyCenter - sCenterY * scale
		fitToBounds(true, satTemp!!)
		return satTemp!!.vTranslate
	}

	@JvmSynthetic
	internal fun limitedSCenter(sCenterX: Float, sCenterY: Float, scale: Float, sTarget: PointF) =
		sTarget.also { target ->
			val vTranslate: PointF = vTranslateForSCenter(sCenterX, sCenterY, scale)
			val vxCenter = paddingLeft + (width - paddingRight - paddingLeft) / 2
			val vyCenter = paddingTop + (height - paddingBottom - paddingTop) / 2
			val sx = (vxCenter - vTranslate.x) / scale
			val sy = (vyCenter - vTranslate.y) / scale
			target.set(sx, sy)
		}

	@JvmSynthetic
	internal fun fitToBounds(center: Boolean) {
		var init = false
		if (vTranslate == null) {
			init = true
			vTranslate = PointF(0f, 0f)
		}
		if (satTemp == null) {
			satTemp = ScaleAndTranslate(0f, PointF(0f, 0f))
		}
		satTemp!!.scale = scale
		satTemp!!.vTranslate.set(vTranslate!!)
		fitToBounds(center, satTemp!!)
		scale = satTemp!!.scale
		vTranslate!!.set(satTemp!!.vTranslate)
		if (init && minimumScaleType != SCALE_TYPE_START) {
			vTranslate!!.set(vTranslateForSCenter(sWidth() / 2f, sHeight() / 2f, scale))
		}
	}

	private fun preDraw() {
		if (width == 0 || height == 0 || sWidth <= 0 || sHeight <= 0) {
			return
		}

		val pendingCenter = sPendingCenter
		if (pendingCenter != null && pendingScale != null) {
			scale = pendingScale ?: 1f
			if (vTranslate == null) {
				vTranslate = PointF()
			}
			checkNotNull(vTranslate).set(
				(width / 2f) - (scale * pendingCenter.x),
				(height / 2f) - (scale * pendingCenter.y),
			)
			sPendingCenter = null
			pendingScale = null
			fitToBounds(true)
			refreshRequiredTiles(load = true)
		}

		fitToBounds(false)
	}

	private fun processAnimation() {
		val animation = anim
		if (animation?.vFocusStart == null) {
			return
		}
		val scaleBefore = scale
		if (vTranslateBefore == null) {
			vTranslateBefore = PointF(0f, 0f)
		}
		vTranslateBefore!!.set(vTranslate!!)
		var scaleElapsed = System.currentTimeMillis() - animation.time
		val finished = scaleElapsed > animation.duration
		scaleElapsed = min(scaleElapsed, animation.duration)
		scale = animation.interpolate(
			scaleElapsed,
			animation.scaleStart,
			animation.scaleEnd - animation.scaleStart,
		)

		val vFocusNowX: Float = animation.interpolate(
			scaleElapsed,
			animation.vFocusStart.x,
			animation.vFocusEnd.x - animation.vFocusStart.x,
		)
		val vFocusNowY: Float = animation.interpolate(
			scaleElapsed,
			animation.vFocusStart.y,
			animation.vFocusEnd.y - animation.vFocusStart.y,
		)
		vTranslate?.run {
			x -= sourceToViewX(animation.sCenterEnd.x) - vFocusNowX
			y -= sourceToViewY(animation.sCenterEnd.y) - vFocusNowY
		}

		fitToBounds(finished || animation.scaleStart == animation.scaleEnd)
		sendStateChanged(scaleBefore, vTranslateBefore!!, animation.origin)
		refreshRequiredTiles(load = finished)
		if (finished) {
			try {
				animation.listener?.onComplete()
			} catch (e: Exception) {
				Log.w(TAG, "Error thrown by animation listener", e)
			}
			anim = null
		}
		invalidate()
	}

	public fun getState(): ImageViewState? {
		return if (vTranslate != null && sWidth > 0 && sHeight > 0) {
			val center = getCenter() ?: return null
			ImageViewState(scale, center.x, center.y, orientation)
		} else null
	}

	public fun setScaleAndCenter(scale: Float, sCenter: PointF) {
		anim = null
		pendingScale = scale
		sPendingCenter = sCenter
		sRequestedCenter = sCenter
		invalidate()
	}

	public fun animateScaleAndCenter(scale: Float, sCenter: PointF): AnimationBuilder? {
		return if (isReady) {
			AnimationBuilder(this, scale, sCenter)
		} else {
			null
		}
	}

	public fun resetScaleAndCenter() {
		anim = null
		pendingScale = limitedScale(0f)
		sPendingCenter = if (isReady) {
			PointF(sWidth() / 2f, sHeight() / 2f)
		} else {
			PointF(0f, 0f)
		}
		invalidate()
	}

	private fun createPaints() {
		if (bitmapPaint == null) {
			bitmapPaint = Paint().apply {
				// FILTER_BITMAP_FLAG: enables bilinear filtering when the bitmap is scaled.
				// This is the most important flag for tile rendering quality — without it,
				// tile edges show nearest-neighbour pixelation during pan and zoom transitions.
				// Note: using Paint(flags) constructor form has a documented quirk on some
				// API levels where flags are not fully propagated; explicit assignment is safer.
				isFilterBitmap = true
				isDither = true
				isAntiAlias = true // No effect on drawBitmap, but kept for subclass paint reuse.
				if (colorFilter != null) {
					this.colorFilter = colorFilter
				}
			}
		}
		if ((debugTextPaint == null || debugLinePaint == null) && isDebugDrawingEnabled) {
			debugTextPaint = Paint().apply {
				textSize = px(12).toFloat()
				color = Color.MAGENTA
				style = Paint.Style.FILL
			}
			debugLinePaint = Paint().apply {
				color = Color.MAGENTA
				style = Paint.Style.STROKE
				strokeWidth = px(1).toFloat()
			}
		}
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		val pan = density * 64f
		return when (keyCode) {
			KeyEvent.KEYCODE_ZOOM_IN,
			KeyEvent.KEYCODE_NUMPAD_ADD,
			KeyEvent.KEYCODE_PLUS -> isZoomEnabled && scaleBy(1.4f)

			KeyEvent.KEYCODE_ZOOM_OUT,
			KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
			KeyEvent.KEYCODE_MINUS -> isZoomEnabled && scaleBy(0.6f)

			KeyEvent.KEYCODE_DPAD_UP -> isPanEnabled && isScaled() && panBy(0f, -pan)
			KeyEvent.KEYCODE_DPAD_UP_RIGHT -> isPanEnabled && isScaled() && panBy(pan, -pan)
			KeyEvent.KEYCODE_DPAD_RIGHT -> isPanEnabled && isScaled() && panBy(pan, 0f)
			KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> isPanEnabled && isScaled() && panBy(pan, pan)
			KeyEvent.KEYCODE_DPAD_DOWN -> isPanEnabled && isScaled() && panBy(0f, pan)
			KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> isPanEnabled && isScaled() && panBy(-pan, pan)
			KeyEvent.KEYCODE_DPAD_LEFT -> isPanEnabled && isScaled() && panBy(-pan, 0f)
			KeyEvent.KEYCODE_DPAD_UP_LEFT -> isPanEnabled && isScaled() && panBy(-pan, -pan)
			KeyEvent.KEYCODE_ESCAPE,
			KeyEvent.KEYCODE_DPAD_CENTER -> isZoomEnabled && isScaled() && scaleBy(0f)

			else -> false
		} || super.onKeyDown(keyCode, event)
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
		return when (keyCode) {
			KeyEvent.KEYCODE_ZOOM_OUT,
			KeyEvent.KEYCODE_ZOOM_IN,
			KeyEvent.KEYCODE_NUMPAD_ADD,
			KeyEvent.KEYCODE_PLUS,
			KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
			KeyEvent.KEYCODE_MINUS -> isZoomEnabled

			KeyEvent.KEYCODE_DPAD_UP,
			KeyEvent.KEYCODE_DPAD_UP_RIGHT,
			KeyEvent.KEYCODE_DPAD_RIGHT,
			KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
			KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
			KeyEvent.KEYCODE_DPAD_LEFT,
			KeyEvent.KEYCODE_DPAD_UP_LEFT -> isPanEnabled && isScaled()

			KeyEvent.KEYCODE_ESCAPE,
			KeyEvent.KEYCODE_DPAD_CENTER -> isZoomEnabled && isScaled()

			else -> super.onKeyDown(keyCode, event)
		}
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean {
		if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
			if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
				val withCtrl = event.metaState and KeyEvent.META_CTRL_MASK != 0
				if (withCtrl) {
					if (!isZoomEnabled) {
						return super.onGenericMotionEvent(event)
					}
					val center = PointF(event.x, event.y)
					val d = event.getAxisValue(MotionEvent.AXIS_VSCROLL) *
						ViewConfigurationCompat.getScaledVerticalScrollFactor(viewConfig, context)
					(animateScaleAndCenter(scale + d, center) ?: return false)
						.withInterpolator(DecelerateInterpolator())
						.withDuration(resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
						.start()
					return true
				} else if (scale > minScale) {
					if (!isPanEnabled) {
						return super.onGenericMotionEvent(event)
					}
					return panBy(
						dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) *
							ViewConfigurationCompat.getScaledHorizontalScrollFactor(viewConfig, context),
						dy = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) *
							ViewConfigurationCompat.getScaledVerticalScrollFactor(viewConfig, context),
					)
				}
			}
		}
		return super.onGenericMotionEvent(event)
	}

	private fun px(px: Int): Int {
		return (density * px).toInt()
	}

	private fun isScaled() = scale > minScale

	private fun Bitmap.fullWidth() = width * _downSampling

	private fun Bitmap.fullHeight() = height * _downSampling

	protected open fun onReady(): Unit = Unit

	protected open fun onDownSamplingChanged(): Unit = Unit

	protected open fun onImageLoaded() {}

	public companion object {

		public const val TILE_SIZE_AUTO: Int = Integer.MAX_VALUE
		internal const val TAG = "SSIV"

		public const val ORIENTATION_USE_EXIF: Int = -1
		public const val ORIENTATION_0: Int = 0
		public const val ORIENTATION_90: Int = 90
		public const val ORIENTATION_180: Int = 180
		public const val ORIENTATION_270: Int = 270

		internal val VALID_ORIENTATIONS = intArrayOf(
			ORIENTATION_0,
			ORIENTATION_90,
			ORIENTATION_180,
			ORIENTATION_270,
			ORIENTATION_USE_EXIF,
		)

		public const val ZOOM_FOCUS_FIXED: Int = 1
		public const val ZOOM_FOCUS_CENTER: Int = 2
		public const val ZOOM_FOCUS_CENTER_IMMEDIATE: Int = 3

		private val VALID_ZOOM_STYLES = ZOOM_FOCUS_FIXED..ZOOM_FOCUS_CENTER_IMMEDIATE

		@Deprecated("Use interpolator api instead")
		public const val EASE_OUT_QUAD: Int = 1

		@Deprecated("Use interpolator api instead")
		public const val EASE_IN_OUT_QUAD: Int = 2

		public const val PAN_LIMIT_INSIDE: Int = 1
		public const val PAN_LIMIT_OUTSIDE: Int = 2
		public const val PAN_LIMIT_CENTER: Int = 3

		private val VALID_PAN_LIMITS = PAN_LIMIT_INSIDE..PAN_LIMIT_CENTER

		public const val SCALE_TYPE_CENTER_INSIDE: Int = 1
		public const val SCALE_TYPE_CENTER_CROP: Int = 2
		public const val SCALE_TYPE_CUSTOM: Int = 3
		public const val SCALE_TYPE_START: Int = 4

		private val VALID_SCALE_TYPES = SCALE_TYPE_CENTER_INSIDE..SCALE_TYPE_START

		public const val ORIGIN_ANIM: Int = 1
		public const val ORIGIN_TOUCH: Int = 2
		public const val ORIGIN_FLING: Int = 3
		public const val ORIGIN_DOUBLE_TAP_ZOOM: Int = 4

		public const val RESTORE_STRATEGY_NONE: Int = 0
		public const val RESTORE_STRATEGY_IMMEDIATE: Int = 1
		public const val RESTORE_STRATEGY_DEFERRED: Int = 2

		@JvmStatic
		@Deprecated("This should be managed in decoder", level = DeprecationLevel.ERROR)
		public var preferredBitmapConfig: Bitmap.Config? = null
	}
}
