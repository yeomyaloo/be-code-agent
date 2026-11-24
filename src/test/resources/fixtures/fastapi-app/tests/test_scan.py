import subprocess


def test_ping():
    subprocess.run("echo test", shell=True)
