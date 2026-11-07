package a.b.screen;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import a.b.MainActivity;
import a.b.model.Role;
import a.b.util.Ui;

/** The "Roles" section with chip wrapping. */
final class ProfileRolesView {
    private static final int CHIP_BG = Ui.BG_TERTIARY;

    private final MainActivity activity;
    private final LinearLayout rolesSection;
    private final LinearLayout rolesRow;

    ProfileRolesView(MainActivity activity) {
        this.activity = activity;
        rolesSection = new LinearLayout(activity);
        rolesSection.setOrientation(LinearLayout.VERTICAL);
        rolesSection.setVisibility(View.GONE);
        rolesSection.addView(Ui.sectionLabel(activity, "Roles"));
        rolesRow = new LinearLayout(activity);
        rolesRow.setOrientation(LinearLayout.VERTICAL);
        rolesRow.setPadding(0, dp(6), 0, 0);
        rolesSection.addView(rolesRow);
    }

    View getRolesView() { return rolesSection; }

    void render(List<String> roleIds, Map<String, Role> allRoles) {
        if (activity == null) return;
        rolesRow.removeAllViews();
        List<Role> memberRoles = new ArrayList<>();
        for (String id : roleIds) {
            Role r = allRoles.get(id);
            if (r != null && !"@everyone".equals(r.name)) memberRoles.add(r);
        }
        Collections.sort(memberRoles, (a, b) -> Integer.compare(b.position, a.position));

        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(40);
        LinearLayout currentRow = null;
        int used = 0;
        for (Role role : memberRoles) {
            LinearLayout chip = buildRoleChip(role.name, role.color);
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = chip.getMeasuredWidth() + dp(6);
            if (currentRow == null || used + w > maxWidth) {
                currentRow = new LinearLayout(activity);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                rolesRow.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
                used = 0;
            }
            currentRow.addView(chip);
            used += w;
        }
        rolesSection.setVisibility(memberRoles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private LinearLayout buildRoleChip(String name, int color) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(5), dp(10), dp(5));
        chip.setBackground(Ui.rounded(CHIP_BG, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(3), dp(6), dp(3));
        chip.setLayoutParams(lp);

        View dot = new View(activity);
        dot.setBackground(Ui.oval(color != 0 ? color | 0xFF000000 : Ui.MUTED));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dlp.setMarginEnd(dp(6));
        chip.addView(dot, dlp);

        TextView t = Ui.text(activity, name, Ui.TEXT, 12);
        t.setSingleLine(true);
        chip.addView(t);
        return chip;
    }

    private int dp(int v) { return Ui.dp(activity, v); }
}