package de.schliweb.makeacopy.ui.export.picker;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fragment responsible for presenting a list of completed scan entries to the user and allowing
 * selection of specific entries. The selected entries can then be returned as a result.
 * <p>
 * This fragment includes a RecyclerView for displaying the completed scans, a ProgressBar to
 * indicate loading progress, and appropriate UI state management for empty data scenarios. The
 * pre-selected and disabled items are passed via fragment arguments, and user interactions are
 * handled through an adapter.
 * <p>
 * Implements {@link CompletedScansPickerAdapter.Callbacks} to handle user interactions like selection
 * changes, enabling/disabling items, and long-press actions for item-specific operations.
 */
public class CompletedScansPickerFragment extends Fragment implements CompletedScansPickerAdapter.Callbacks {

    public static final String RESULT_KEY = "pick_completed_scans";
    public static final String RESULT_IDS = "selected_ids";
    public static final String ARG_ALREADY_SELECTED_IDS = "already_selected_ids";

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyView;
    private Button buttonDone;

    private final Set<String> selectedIds = new HashSet<>();
    private final Set<String> disabledIds = new HashSet<>();
    private CompletedScansPickerAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_completed_scans_picker, container, false);
        recyclerView = root.findViewById(R.id.recycler);
        progressBar = root.findViewById(R.id.progress);
        emptyView = root.findViewById(R.id.empty);
        buttonDone = root.findViewById(R.id.button_done);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        // Accept preselected/disabled IDs from args
        Bundle args = getArguments();
        if (args != null) {
            ArrayList<String> pre = args.getStringArrayList(ARG_ALREADY_SELECTED_IDS);
            if (pre != null) disabledIds.addAll(pre);
        }

        adapter = new CompletedScansPickerAdapter(this);
        recyclerView.setAdapter(adapter);

        buttonDone.setOnClickListener(v -> returnResultAndClose());

        loadItems();
        return root;
    }

    private void loadItems() {
        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);

        // Opportunistic cleanup before listing
        try {
            de.schliweb.makeacopy.data.RegistryCleaner.cleanupOrphans(requireContext().getApplicationContext());
        } catch (Throwable ignore) {
        }
        // Load synchronously for v1 simplicity; list is small
        List<CompletedScan> items = CompletedScansRegistry.get(requireContext()).listAllOrderedByDateDesc();

        progressBar.setVisibility(View.GONE);
        if (items == null || items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            adapter.submitList(new ArrayList<>());
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.submitList(items);
        }
        updateDoneEnabled();
    }

    private void updateDoneEnabled() {
        buttonDone.setEnabled(!selectedIds.isEmpty());
    }

    private void returnResultAndClose() {
        ArrayList<String> ids = new ArrayList<>(selectedIds);
        Bundle result = new Bundle();
        result.putStringArrayList(RESULT_IDS, ids);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        try {
            androidx.navigation.Navigation.findNavController(requireView()).popBackStack();
        } catch (Throwable t) {
            getParentFragmentManager().popBackStack();
        }
    }

    // Callbacks from adapter
    @Override
    public boolean isSelected(@NonNull String id) {
        return selectedIds.contains(id);
    }

    @Override
    public void onItemSelectionChanged(@NonNull String id, boolean selected) {
        if (selected) selectedIds.add(id);
        else selectedIds.remove(id);
        updateDoneEnabled();
    }

    @Override
    public boolean isDisabled(@NonNull String id) {
        return disabledIds.contains(id);
    }

    /**
     * Handles the long press action on an item in the list. This method displays a confirmation
     * dialog to remove the selected item from the registry and its associated files. If the removal
     * is confirmed, it updates the UI to reflect the changes and refreshes the list of items.
     *
     * @param id The unique identifier of the item that was long-pressed.
     */
    @Override
    public void onItemLongPress(@NonNull String id) {
        // Show confirmation dialog to remove from registry
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.remove_from_registry_title)
                .setMessage(R.string.remove_from_registry_message)
                .setPositiveButton(R.string.remove, (d, which) -> {
                    try {
                        de.schliweb.makeacopy.data.RegistryCleaner.removeEntryAndFiles(requireContext().getApplicationContext(), id);
                        android.widget.Toast.makeText(requireContext(), R.string.removed_from_registry_toast, android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                    // Refresh list after removal
                    loadItems();
                })
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .create();
        // Improve dark mode contrast for dialog buttons similar to other dialogs
        dialog.setOnShowListener(dlg -> {
            int nightModeFlags = requireContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                try {
                    int white = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white);
                    if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(white);
                    }
                    if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(white);
                    }
                    if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(white);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        dialog.show();
    }
}
