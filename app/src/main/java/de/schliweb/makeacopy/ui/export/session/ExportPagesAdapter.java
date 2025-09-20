package de.schliweb.makeacopy.ui.export.session;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.R;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Adapter for a RecyclerView to display and manage a list of scanned pages for export purposes.
 * <p>
 * This adapter handles the display of pages, facilitates user interactions such as selecting,
 * removing, or reordering items, and communicates events back to the specified callbacks interface.
 * The pages are represented using the `CompletedScan` class and displayed in a custom
 * layout defined in the application.
 */
public class ExportPagesAdapter extends RecyclerView.Adapter<ExportPagesAdapter.PageVH> {
    // Simple in-memory LRU cache for small thumbnails
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<>(32);

    private static Bitmap decodeSampled(String path, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            int inSampleSize = 1;
            int halfH = opts.outHeight / 2;
            int halfW = opts.outWidth / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = Math.max(1, inSampleSize);
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, real);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap ThumbCache_get(String key) {
        return key == null ? null : THUMB_CACHE.get(key);
    }

    private static void ThumbCache_put(String key, Bitmap bmp) {
        if (key != null && bmp != null) THUMB_CACHE.put(key, bmp);
    }

    /**
     * Defines callback methods for handling user interactions and actions
     * within a RecyclerView adapter.
     * <p>
     * This interface is intended to be implemented by components that need
     * to handle specific events such as item removal, item clicks, and
     * reordering operations in a list of items.
     */
    public interface Callbacks {
        /**
         * Handles the event when the remove button is clicked for a specific item in the list.
         * Typically used to remove the item at the given position from the data set or perform
         * associated actions.
         *
         * @param position The position of the item in the list that was clicked.
         */
        void onRemoveClicked(int position);

        /**
         * Handles the event when a page in the list is clicked.
         * Typically used to select the page at the given position.
         *
         * @param position The position of the clicked page in the list.
         */
        void onPageClicked(int position);

        /**
         * Handles the reordering of items in a list, typically in response to user actions such as drag-and-drop operations.
         * This method is intended to update the logical order of the items and perform any related processing as necessary.
         *
         * @param fromPosition The initial position of the item before reordering.
         * @param toPosition   The new position of the item after reordering.
         */
        void onReorder(int fromPosition, int toPosition);

        /**
         * Requests running OCR for a specific page (inline OCR action).
         *
         * @param position Index of the page in the adapter.
         */
        void onOcrRequested(int position);
    }

    /**
     * A list that holds instances of {@link CompletedScan}, representing completed scans available
     * for processing, exporting, or display in the UI.
     * <p>
     * This list is used to manage the data set for the adapter, which binds the scan
     * details to the corresponding UI elements in a RecyclerView or similar component.
     * <p>
     * The list is immutable, as it is declared final and initialized with an {@link ArrayList}.
     * Modifications to the list content should be performed by replacing it with new instances
     * using methods like {@link #submitList(List)}.
     */
    private final List<CompletedScan> items = new ArrayList<>();
    /**
     * A reference to the callback interface that defines methods for handling user interactions
     * and actions within the adapter.
     * <p>
     * The callbacks are used for:
     * - Detecting and handling remove button clicks for specific items in the RecyclerView.
     * - Handling page click events within the list.
     * - Managing item reordering operations, such as drag-and-drop functionality.
     * <p>
     * This field is passed during the construction of the adapter and represents the bridge
     * through which the adapter communicates user actions back to the hosting component.
     */
    private final Callbacks callbacks;

    /**
     * Constructs an instance of the ExportPagesAdapter, initializing it with the given callback
     * interface to handle user interactions and actions within a RecyclerView context.
     *
     * @param callbacks The callback interface used to handle events such as item removal,
     *                  item clicks, and reordering. This parameter should not be null.
     */
    public ExportPagesAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
        setHasStableIds(false);
    }

    /**
     * Updates the current list of items with the provided list of completed scans.
     * Clears the existing items and adds all elements from the new list, then notifies
     * the adapter that the dataset has changed.
     *
     * @param list The new list of CompletedScan objects to replace the current items.
     *             If null, the list will be cleared.
     */
    public void submitList(List<CompletedScan> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    /**
     * Handles the movement of an item from one position to another within a dataset.
     * This method swaps the elements at the specified positions, updates the RecyclerView,
     * and invokes a callback if one is defined.
     *
     * @param fromPosition The initial position of the item to be moved. Must be within the bounds of the dataset.
     * @param toPosition   The target position to which the item should be moved. Must be within the bounds of the dataset.
     * @return {@code true} if the item was successfully moved; {@code false} otherwise (e.g., if the positions are invalid or the same).
     */
    public boolean onItemMove(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= items.size() || toPosition >= items.size())
            return false;
        if (fromPosition == toPosition) return false;
        Collections.swap(items, fromPosition, toPosition);
        notifyItemMoved(fromPosition, toPosition);
        if (callbacks != null) {
            callbacks.onReorder(fromPosition, toPosition);
        }
        return true;
    }

    /**
     * Creates and returns a new ViewHolder instance representing a single item in the RecyclerView.
     * The method inflates the layout corresponding to the specified item view type and initializes
     * the ViewHolder with it.
     *
     * @param parent   The parent ViewGroup into which the new View will be added after it is bound to
     *                 an adapter position. It is typically the RecyclerView itself.
     * @param viewType The view type of the new View. This allows for different layouts to be used for
     *                 items in the RecyclerView if the adapter requires multiple view types.
     * @return A new instance of {@link PageVH}, which is initialized with the inflated item view.
     */
    @NonNull
    @Override
    public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_export_page, parent, false);
        return new PageVH(v);
    }

    /**
     * Binds data to the given {@link PageVH} ViewHolder at the specified position.
     * This method updates the title, thumbnail, and click listeners of the ViewHolder
     * based on the data at the given position from the {@code items} list.
     *
     * @param h        The {@link PageVH} ViewHolder to which data is to be bound.
     *                 Represents a single item view in the RecyclerView.
     * @param position The position of the item in the dataset. Used to retrieve data from
     *                 the {@code items} list and to define the item's display context
     *                 and functionality within the ViewHolder.
     */
    @Override
    public void onBindViewHolder(@NonNull PageVH h, int position) {
        CompletedScan s = items.get(position);
        // Title like "01 • HH:mm" (time only, no date)
        String title = String.format(Locale.getDefault(), "%02d • %s",
                position + 1,
                new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.createdAt())));
        h.title.setText(title);

        // Thumbnail strategy: prefer in-memory bitmap; else decode sampled from thumbPath or filePath
        Bitmap bmp = s.inMemoryBitmap();
        if (bmp != null) {
            h.thumb.setImageBitmap(bmp);
        } else {
            Bitmap cached = ThumbCache_get(s.thumbPath() != null ? s.thumbPath() : s.filePath());
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                h.thumb.setImageResource(R.drawable.ic_image);
                String path = (s.thumbPath() != null) ? s.thumbPath() : s.filePath();
                if (path != null) {
                    // decode sampled to roughly view size
                    Bitmap decoded = decodeSampled(path, 96, 128);
                    if (decoded != null) {
                        ThumbCache_put(path, decoded);
                        h.thumb.setImageBitmap(decoded);
                    }
                }
            }
        }

        // OCR badge: show [OCR] if ocrTextPath present & file exists; otherwise [⚠]
        String badge = null;
        int badgeBg = 0x33000000; // default semi-transparent
        String ocrPath = s.ocrTextPath();
        boolean hasOcr = false;
        if (ocrPath != null) {
            try {
                java.io.File f = new java.io.File(ocrPath);
                hasOcr = f.exists() && f.isFile();
            } catch (Throwable ignore) {
            }
        }
        if (hasOcr) {
            badge = "[OCR]";
            badgeBg = 0x8032CD32; // semi green
        } else {
            badge = "[⚠]";
            badgeBg = 0x80FFA500; // semi orange
        }
        h.badge.setText(badge);
        h.badge.setBackgroundColor(badgeBg);
        // Inline OCR action: if missing OCR, clicking the badge requests OCR for this page
        if (!hasOcr && callbacks != null) {
            h.badge.setOnClickListener(v -> callbacks.onOcrRequested(h.getAdapterPosition()));
        } else {
            h.badge.setOnClickListener(null);
        }

        h.buttonRemove.setOnClickListener(v -> {
            if (callbacks != null) callbacks.onRemoveClicked(h.getAdapterPosition());
        });
        // Click on the item (or thumbnail) selects the page
        View.OnClickListener select = v -> {
            if (callbacks != null) callbacks.onPageClicked(h.getAdapterPosition());
        };
        h.itemView.setOnClickListener(select);
        h.thumb.setOnClickListener(select);
    }

    /**
     * Retrieves the total number of items currently managed by the adapter.
     *
     * @return The total count of items in the dataset managed by this adapter.
     */
    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Represents a ViewHolder for managing and displaying an individual page item
     * in a RecyclerView within the ExportPagesAdapter class. This ViewHolder contains
     * references to the UI components of a single item, facilitating the efficient
     * binding of data and handling of user interactions.
     * <p>
     * Fields:
     * - thumb: An ImageView used for displaying a thumbnail image associated with the page.
     * - title: A TextView used for displaying the title or label of the page.
     * - buttonRemove: An ImageButton used for handling user actions, such as removing the page.
     * <p>
     * Constructor:
     * Initializes the PageVH instance, binding the UI components to their corresponding
     * views within the item layout by using their resource IDs.
     *
     * @see ExportPagesAdapter
     */
    static class PageVH extends RecyclerView.ViewHolder {
        ImageView thumb;
        TextView title;
        ImageButton buttonRemove;
        TextView badge;

        PageVH(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.page_thumb);
            title = itemView.findViewById(R.id.page_title);
            buttonRemove = itemView.findViewById(R.id.button_remove);
            badge = itemView.findViewById(R.id.page_badge);
        }
    }
}
