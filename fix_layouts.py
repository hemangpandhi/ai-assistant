import glob
import re

for filepath in glob.glob("app/src/main/res/layout/assistant_overlay*.xml"):
    with open(filepath, "r") as f:
        content = f.read()

    # If it has android:layout_width="match_parent" on cardPopup, we should fix it.
    if 'android:id="@+id/cardPopup"' in content and 'android:layout_width="match_parent"' in content.split('android:id="@+id/cardPopup"')[1][:200]:
        
        # Replace match_parent with 0dp
        content = re.sub(
            r'(android:id="@+id/cardPopup"[^>]*?android:layout_width=")match_parent(")',
            r'\g<1>0dp\g<2>',
            content
        )
        
        # Replace android:layout_gravity="bottom|center_horizontal" with constraint layout attributes
        content = re.sub(
            r'android:layout_gravity="bottom\|center_horizontal"',
            'app:layout_constraintBottom_toBottomOf="parent"\n        app:layout_constraintStart_toStartOf="parent"\n        app:layout_constraintEnd_toEndOf="parent"',
            content
        )
        
        # Replace root FrameLayout with ConstraintLayout
        content = content.replace("<FrameLayout", "<androidx.constraintlayout.widget.ConstraintLayout")
        content = content.replace("</FrameLayout>", "</androidx.constraintlayout.widget.ConstraintLayout>")
        
        with open(filepath, "w") as f:
            f.write(content)
        print(f"Fixed {filepath}")
