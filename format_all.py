import os
import subprocess

java_files = []
for root, dirs, files in os.walk('src'):
    for file in files:
        if file.endswith('.java'):
            java_files.append(os.path.join(root, file))

batch_size = 100
for i in range(0, len(java_files), batch_size):
    batch = java_files[i:i+batch_size]
    cmd = ['java', '-jar', 'google-java-format.jar', '--replace'] + batch
    print(f"Formatting batch {i//batch_size + 1}...")
    subprocess.run(cmd, check=True)
print("Formatting complete.")
