import asyncio

import nmap


async def run_nmap(target: str) -> str:
    proc = await asyncio.create_subprocess_exec("nmap", "-sV", target, stdout=asyncio.subprocess.PIPE)
    out, _ = await proc.communicate()
    return out.decode()


def scan_with_lib(hosts: str):
    nm = nmap.PortScanner()
    nm.scan(hosts=hosts, arguments="-sV")
    return nm.all_hosts()
