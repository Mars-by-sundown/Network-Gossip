start cmd /c javac Gossip.java
SLEEP 3
for /l %%x in (0, 1, 2) do (
   start cmd /k java Gossip %%x
)